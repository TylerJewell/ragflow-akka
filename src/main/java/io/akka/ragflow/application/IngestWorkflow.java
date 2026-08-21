package io.akka.ragflow.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.workflow.Workflow;
import io.akka.ragflow.domain.Chunk;
import io.akka.ragflow.domain.ChunkId;
import io.akka.ragflow.domain.Chunker;
import io.akka.ragflow.domain.DocumentCommand;
import io.akka.ragflow.domain.DocumentState;
import io.akka.ragflow.domain.Embedder;
import io.akka.ragflow.domain.FileType;
import io.akka.ragflow.domain.TaskCommand;
import io.akka.ragflow.domain.TaskStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * One task's pipeline — parse, chunk, embed, index — driven to completion or to abandonment
 * (SPEC-001 §3 rules 12-22). One workflow instance per task, keyed by taskId.
 *
 * <p>Retry is the workflow engine's own step retry (SPEC-001 §4 decision 4): {@code runPipeline}
 * is retried up to twice on a thrown exception, and each invocation — the original and every
 * retry — calls {@link TaskEntity#beginAttempt}, which is what actually counts dispatches and
 * decides abandonment (rule 16). Three invocations therefore either finish the task or abandon
 * it; a fourth is never reached by design, but {@code failoverTo(abandonTask)} exists as a
 * backstop in case a non-abandonment exception survives all retries.
 */
@Component(id = "ingest")
public class IngestWorkflow extends Workflow<IngestWorkflow.State> {

  public record State(
      String taskId,
      String docId,
      int fromPage,
      int toPage,
      String digest,
      String reuseFromTaskId,
      String phase) {}

  public record StartCommand(
      String docId, int fromPage, int toPage, String digest, String reuseFromTaskId) {}

  private final ComponentClient componentClient;
  private final String taskId;

  public IngestWorkflow(ComponentClient componentClient, akka.javasdk.workflow.WorkflowContext context) {
    this.componentClient = componentClient;
    this.taskId = context.workflowId();
  }

  @Override
  public WorkflowSettings settings() {
    return WorkflowSettings.builder()
        .defaultStepTimeout(Duration.ofSeconds(30))
        .stepRecovery(
            IngestWorkflow::runPipeline,
            RecoverStrategy.maxRetries(2).failoverTo(IngestWorkflow::abandonTask))
        .build();
  }

  public Effect<Done> start(StartCommand cmd) {
    var state =
        new State(taskId, cmd.docId(), cmd.fromPage(), cmd.toPage(), cmd.digest(), cmd.reuseFromTaskId(), "running");
    if (cmd.reuseFromTaskId() != null) {
      return effects().updateState(state).transitionTo(IngestWorkflow::doReuse).thenReply(Done.getInstance());
    }
    return effects().updateState(state).transitionTo(IngestWorkflow::runPipeline).thenReply(Done.getInstance());
  }

  public ReadOnlyEffect<State> status() {
    return effects().reply(currentState());
  }

  /**
   * Rules 20-21: parse, chunk, embed, index, in that fixed order, with progress reported after
   * each stage. Rule 16: the first thing every invocation of this step does is dispatch through
   * {@link TaskEntity#beginAttempt}.
   */
  private StepEffect runPipeline() {
    var state = currentState();
    var attempt =
        componentClient.forEventSourcedEntity(taskId).method(TaskEntity::beginAttempt).invoke();
    if (attempt.abandoned()) {
      return stepEffects().thenTransitionTo(IngestWorkflow::abandonTask);
    }

    reportTaskProgress(state, TaskStatus.RUNNING, 0.2, "Parsing...", 0, 0);
    List<Chunker.Section> sections = readSections(state);

    reportTaskProgress(state, TaskStatus.RUNNING, 0.5, "Chunking...", 0, 0);
    var doc =
        componentClient.forEventSourcedEntity(state.docId()).method(DocumentEntity::read).invoke();
    boolean isMarkdown = doc.fileType() == FileType.MARKDOWN;
    List<Chunker.ChunkDraft> drafts = Chunker.naiveMerge(sections, doc.parserConfig(), isMarkdown);

    reportTaskProgress(state, TaskStatus.RUNNING, 0.8, "Embedding...", 0, 0);
    List<Chunk> chunks = new ArrayList<>();
    for (Chunker.ChunkDraft d : drafts) {
      String id = ChunkId.of(d.content(), state.docId());
      chunks.add(new Chunk(id, state.docId(), taskId, doc.kbId(), d.content(), d.tokenNum(), d.pages(), Embedder.embed(d.content())));
    }

    componentClient
        .forEventSourcedEntity(taskId)
        .method(TaskEntity::index)
        .invoke(new TaskCommand.Index(chunks));

    int tokenSum = chunks.stream().mapToInt(Chunk::tokenNum).sum();
    // TaskEntity::index already moved the task to DONE (rules 20-22) — reporting progress to it
    // again here would replay ProgressReported on top of that and, since applyEvent's
    // ProgressReported case always lands in RUNNING, silently undo the DONE transition. Only
    // DocumentEntity's rollup still needs telling.
    reportToDocument(state, TaskStatus.DONE, 1.0, chunks.size(), tokenSum);

    return stepEffects().updateState(withPhase(state, "done")).thenEnd();
  }

  /** Rule 14: a digest-matched previous task's chunks are attached without re-running the pipeline. */
  private StepEffect doReuse() {
    var state = currentState();
    List<Chunk> chunks =
        componentClient
            .forEventSourcedEntity(state.reuseFromTaskId())
            .method(TaskEntity::readChunks)
            .invoke();
    componentClient
        .forEventSourcedEntity(taskId)
        .method(TaskEntity::reuse)
        .invoke(new TaskCommand.Reuse(chunks));
    int tokenSum = chunks.stream().mapToInt(Chunk::tokenNum).sum();
    // Same reason as runPipeline above: TaskEntity::reuse already set DONE.
    reportToDocument(state, TaskStatus.DONE, 1.0, chunks.size(), tokenSum);
    return stepEffects().updateState(withPhase(state, "done")).thenEnd();
  }

  /** Rule 16: the dispatch that pushed retryCount to 3 — abandoned rather than processed. */
  private StepEffect abandonTask() {
    var state = currentState();
    // TaskEntity::beginAttempt already persisted Abandoned (FAIL, -1.0) before this step was
    // reached; only DocumentEntity's rollup still needs telling.
    reportToDocument(state, TaskStatus.FAIL, -1.0, 0, 0);
    return stepEffects().updateState(withPhase(state, "abandoned")).thenEnd();
  }

  /** Intermediate progress: both the task (rule 15) and the document rollup (rules 17-19) are told. */
  private void reportTaskProgress(
      State state, TaskStatus status, double progress, String msg, int chunkCount, int tokenCount) {
    componentClient
        .forEventSourcedEntity(taskId)
        .method(TaskEntity::reportProgress)
        .invoke(new TaskCommand.ReportProgress(progress, msg));
    reportToDocument(state, status, progress, chunkCount, tokenCount);
  }

  private void reportToDocument(State state, TaskStatus status, double progress, int chunkCount, int tokenCount) {
    componentClient
        .forEventSourcedEntity(state.docId())
        .method(DocumentEntity::reportTaskProgress)
        .invoke(
            new DocumentCommand.ReportTaskProgress(
                taskId, state.fromPage(), state.toPage(), state.digest(), status, progress, chunkCount, tokenCount));
  }

  private List<Chunker.Section> readSections(State state) {
    DocumentState doc =
        componentClient.forEventSourcedEntity(state.docId()).method(DocumentEntity::read).invoke();
    if (doc.fileType() != FileType.PDF) {
      return List.of(new Chunker.Section(doc.sections().get(0), null));
    }
    List<Chunker.Section> sections = new ArrayList<>();
    for (int page = state.fromPage(); page < state.toPage(); page++) {
      sections.add(new Chunker.Section(doc.sections().get(page), page + 1));
    }
    return sections;
  }

  private static State withPhase(State s, String phase) {
    return new State(s.taskId(), s.docId(), s.fromPage(), s.toPage(), s.digest(), s.reuseFromTaskId(), phase);
  }
}
