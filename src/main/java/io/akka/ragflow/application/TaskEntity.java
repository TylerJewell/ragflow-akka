package io.akka.ragflow.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.ragflow.domain.Chunk;
import io.akka.ragflow.domain.ProgressRule;
import io.akka.ragflow.domain.TaskCommand;
import io.akka.ragflow.domain.TaskEvent;
import io.akka.ragflow.domain.TaskState;
import io.akka.ragflow.domain.TaskStatus;
import java.util.List;

/**
 * One task — a document's page range (or whole document) being parsed, chunked, embedded and
 * indexed (SPEC-001 §2 Task, §3 rules 12-22).
 *
 * <p>The retry-then-abandon rule (rule 16) lives here rather than in the workflow that dispatches
 * it: {@link #beginAttempt} is what a real "dispatch" is, called once per {@code IngestWorkflow}
 * step invocation including retries, and it is the entity — not the workflow's own retry
 * counter — that decides abandonment, so {@code retryCount} stays an observable property of the
 * task rather than an internal workflow detail (SPEC-001 §4 decision 4).
 */
@Component(id = "task")
public class TaskEntity extends EventSourcedEntity<TaskState, TaskEvent> {

  public record AttemptResult(boolean abandoned, int retryCount) {}

  private final String taskId;

  public TaskEntity(akka.javasdk.eventsourcedentity.EventSourcedEntityContext context) {
    this.taskId = context.entityId();
  }

  @Override
  public TaskState emptyState() {
    return TaskState.empty(taskId);
  }

  public Effect<Done> queue(TaskCommand.Queue cmd) {
    if (currentState().isQueued()) {
      return effects().error("task '" + taskId + "' is already queued");
    }
    return effects()
        .persist(new TaskEvent.Queued(cmd.docId(), cmd.fromPage(), cmd.toPage(), cmd.digest()))
        .thenReply(s -> Done.getInstance());
  }

  /** Rule 16: every dispatch increments {@code retryCount}; the 3rd is abandoned, not run. */
  public Effect<AttemptResult> beginAttempt() {
    if (!currentState().isQueued()) {
      return effects().error("task '" + taskId + "' has not been queued");
    }
    if (currentState().status() == TaskStatus.DONE || currentState().status() == TaskStatus.FAIL
        || currentState().status() == TaskStatus.CANCEL) {
      return effects().error("task '" + taskId + "' is already " + currentState().status());
    }
    int nextCount = currentState().retryCount() + 1;
    if (nextCount >= 3) {
      return effects()
          .persist(
              new TaskEvent.AttemptStarted(nextCount),
              new TaskEvent.Abandoned("Task is abandoned after 3 times attempts."))
          .thenReply(s -> new AttemptResult(true, nextCount));
    }
    return effects()
        .persist(new TaskEvent.AttemptStarted(nextCount))
        .thenReply(s -> new AttemptResult(false, nextCount));
  }

  public Effect<Done> reportProgress(TaskCommand.ReportProgress cmd) {
    if (!ProgressRule.accepts(currentState().progress(), cmd.progress())) {
      return effects().reply(Done.getInstance()); // stale update: silently ignored, per rule 15.
    }
    return effects()
        .persist(new TaskEvent.ProgressReported(cmd.progress(), cmd.msg()))
        .thenReply(s -> Done.getInstance());
  }

  /** Rule 14: digest-matched reuse. No chunk/embed/index stages run for this task. */
  public Effect<Done> reuse(TaskCommand.Reuse cmd) {
    return effects()
        .persist(new TaskEvent.Reused(currentState().docId(), cmd.chunks()))
        .thenReply(s -> Done.getInstance());
  }

  /** Rules 20-22: the index stage's output, written atomically with the DONE transition. */
  public Effect<Done> index(TaskCommand.Index cmd) {
    return effects()
        .persist(new TaskEvent.Indexed(currentState().docId(), cmd.chunks()))
        .thenReply(s -> Done.getInstance());
  }

  public Effect<Done> fail(TaskCommand.Fail cmd) {
    return effects().persist(new TaskEvent.Failed(cmd.msg())).thenReply(s -> Done.getInstance());
  }

  public Effect<Done> cancel() {
    return effects().persist(new TaskEvent.Canceled()).thenReply(s -> Done.getInstance());
  }

  public ReadOnlyEffect<TaskState> read() {
    if (!currentState().isQueued()) {
      return effects().error("task '" + taskId + "' has not been queued");
    }
    return effects().reply(currentState());
  }

  public ReadOnlyEffect<List<Chunk>> readChunks() {
    if (currentState().status() != TaskStatus.DONE) {
      return effects().error("task '" + taskId + "' is not DONE, has no finished chunks to read");
    }
    return effects().reply(currentState().chunks());
  }

  @Override
  public TaskState applyEvent(TaskEvent event) {
    return switch (event) {
      case TaskEvent.Queued e ->
          new TaskState(taskId, e.docId(), e.fromPage(), e.toPage(), e.digest(), TaskStatus.RUNNING, 0.0, "", 0, List.of());
      case TaskEvent.AttemptStarted e -> withRetryCount(e.retryCount());
      case TaskEvent.ProgressReported e -> withProgress(TaskStatus.RUNNING, e.progress(), e.msg());
      case TaskEvent.Reused e -> withChunks(TaskStatus.DONE, 1.0, "Reused previous task's chunks.", e.chunks());
      case TaskEvent.Indexed e -> withChunks(TaskStatus.DONE, 1.0, "Task done.", e.chunks());
      case TaskEvent.Abandoned e -> withProgress(TaskStatus.FAIL, -1.0, e.msg());
      case TaskEvent.Failed e -> withProgress(TaskStatus.FAIL, -1.0, e.msg());
      case TaskEvent.Canceled e -> withProgress(TaskStatus.CANCEL, currentState().progress(), "Canceled.");
    };
  }

  private TaskState withRetryCount(int retryCount) {
    var s = currentState();
    return new TaskState(
        s.taskId(), s.docId(), s.fromPage(), s.toPage(), s.digest(), s.status(), s.progress(),
        s.progressMsg(), retryCount, s.chunks());
  }

  private TaskState withProgress(TaskStatus status, double progress, String msg) {
    var s = currentState();
    return new TaskState(
        s.taskId(), s.docId(), s.fromPage(), s.toPage(), s.digest(), status, progress, msg,
        s.retryCount(), s.chunks());
  }

  private TaskState withChunks(TaskStatus status, double progress, String msg, List<Chunk> chunks) {
    var s = currentState();
    return new TaskState(
        s.taskId(), s.docId(), s.fromPage(), s.toPage(), s.digest(), status, progress, msg,
        s.retryCount(), chunks);
  }
}
