package io.akka.ragflow.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.ragflow.domain.DocumentCommand;
import io.akka.ragflow.domain.DocumentEvent;
import io.akka.ragflow.domain.DocumentRollup;
import io.akka.ragflow.domain.DocumentState;
import io.akka.ragflow.domain.DocumentStatus;
import io.akka.ragflow.domain.TaskDescriptor;
import io.akka.ragflow.domain.TaskDigest;
import io.akka.ragflow.domain.TaskSplitter;
import io.akka.ragflow.domain.TaskStatus;
import io.akka.ragflow.domain.TaskSummary;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One ingested document — its content, chunking configuration, and the task-splitting / progress
 * rollup state machine that drives it from {@code UNSTART} to {@code DONE} or {@code FAIL}
 * (SPEC-001 §2 Document, §3 rules 12-19).
 */
@Component(id = "document")
public class DocumentEntity extends EventSourcedEntity<DocumentState, DocumentEvent> {

  private final String docId;

  public DocumentEntity(akka.javasdk.eventsourcedentity.EventSourcedEntityContext context) {
    this.docId = context.entityId();
  }

  @Override
  public DocumentState emptyState() {
    return DocumentState.empty(docId);
  }

  public Effect<Done> create(DocumentCommand.Create cmd) {
    if (currentState().exists()) {
      return effects().error("document '" + docId + "' already exists");
    }
    return effects()
        .persist(new DocumentEvent.Created(cmd.kbId(), cmd.name(), cmd.fileType(), cmd.sections(), cmd.parserConfig()))
        .thenReply(s -> Done.getInstance());
  }

  /**
   * Rule 12: split into task page ranges. Rule 14: a range whose digest matches a previously
   * completed task is flagged for reuse right here, from state this entity already holds, so the
   * caller never has to re-derive it.
   */
  public Effect<List<TaskDescriptor>> parse() {
    if (!currentState().exists()) {
      return effects().error("document '" + docId + "' does not exist");
    }
    var config = currentState().parserConfig();
    var ranges = TaskSplitter.split(currentState().fileType(), currentState().sections().size(), config.taskPageSize());
    // A new task id per parse() call (rather than a page-index-only id) because a workflow
    // instance cannot be restarted once it has ended: re-parsing the same range must dispatch a
    // *new* IngestWorkflow, even when rule 14 goes on to reuse the old chunks without redoing
    // any work.
    int generation = currentState().parseGeneration() + 1;

    List<TaskDescriptor> descriptors = new ArrayList<>();
    for (int i = 0; i < ranges.size(); i++) {
      var range = ranges.get(i);
      String digest = TaskDigest.of(config, docId, range.fromPage(), range.toPage());
      String taskId = docId + "/g" + generation + "/t" + i;
      Optional<TaskSummary> reusable = currentState().findReusable(range.fromPage(), digest);
      descriptors.add(
          new TaskDescriptor(
              taskId, range.fromPage(), range.toPage(), digest,
              reusable.map(TaskSummary::taskId).orElse(null)));
    }
    return effects()
        .persist(new DocumentEvent.ParseStarted(generation, descriptors))
        .thenReply(s -> descriptors);
  }

  public Effect<Done> reportTaskProgress(DocumentCommand.ReportTaskProgress cmd) {
    if (!currentState().exists()) {
      return effects().error("document '" + docId + "' does not exist");
    }
    return effects()
        .persist(
            new DocumentEvent.TaskProgressReported(
                cmd.taskId(), cmd.fromPage(), cmd.toPage(), cmd.digest(), cmd.status(),
                cmd.progress(), cmd.chunkCount(), cmd.tokenCount()))
        .thenReply(s -> Done.getInstance());
  }

  /** Rule 19: cancellation is terminal from the rollup's point of view. */
  public Effect<Done> cancel() {
    if (!currentState().exists()) {
      return effects().error("document '" + docId + "' does not exist");
    }
    return effects().persist(new DocumentEvent.Canceled()).thenReply(s -> Done.getInstance());
  }

  public ReadOnlyEffect<DocumentState> read() {
    if (!currentState().exists()) {
      return effects().error("document '" + docId + "' does not exist");
    }
    return effects().reply(currentState());
  }

  @Override
  public DocumentState applyEvent(DocumentEvent event) {
    return switch (event) {
      case DocumentEvent.Created e ->
          new DocumentState(
              docId, e.kbId(), e.name(), e.fileType(), e.sections(), e.parserConfig(),
              DocumentStatus.UNSTART, 0.0, "", 0, 0, List.of(), 0);
      case DocumentEvent.ParseStarted e -> withQueuedTasks(e.generation(), e.tasks());
      case DocumentEvent.TaskProgressReported e -> withTaskProgress(e);
      case DocumentEvent.Canceled e -> withCancel();
    };
  }

  /**
   * Row 15: a manual re-parse always wipes prior tasks before re-queuing, so the rollup in rules
   * 17-19 only ever averages the *current* generation's tasks — a superseded generation's DONE
   * task would otherwise silently drag the mean toward 1.0 forever. Reuse (rule 14) is unaffected
   * because {@code parse()} already resolved it against the pre-reset state above.
   */
  private DocumentState withQueuedTasks(int generation, List<TaskDescriptor> tasks) {
    DocumentState s = currentState().withParseGeneration(generation).withTasksCleared();
    for (TaskDescriptor d : tasks) {
      TaskStatus status = d.reuseFromTaskId() != null ? TaskStatus.DONE : TaskStatus.RUNNING;
      double progress = d.reuseFromTaskId() != null ? 1.0 : 0.0;
      s = s.withTask(new TaskSummary(d.taskId(), d.fromPage(), d.toPage(), d.digest(), status, progress, 0, 0));
    }
    var rollup = DocumentRollup.of(s.tasks(), DocumentStatus.RUNNING, 0.0);
    return s.withRollup(rollup, "Task is queued...");
  }

  private DocumentState withTaskProgress(DocumentEvent.TaskProgressReported e) {
    DocumentState s =
        currentState()
            .withTask(
                new TaskSummary(
                    e.taskId(), e.fromPage(), e.toPage(), e.digest(), e.status(), e.progress(),
                    e.chunkCount(), e.tokenCount()));
    var rollup = DocumentRollup.of(s.tasks(), s.status(), s.progress());
    return s.withRollup(rollup, s.progressMsg());
  }

  private DocumentState withCancel() {
    DocumentState s = currentState();
    return new DocumentState(
        s.id(), s.kbId(), s.name(), s.fileType(), s.sections(), s.parserConfig(),
        DocumentStatus.CANCEL, s.progress(), "Canceled.", s.chunkNum(), s.tokenNum(), s.tasks(), s.parseGeneration());
  }
}
