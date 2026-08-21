package io.akka.ragflow.domain;

import akka.javasdk.annotations.TypeName;
import java.util.List;

/** What a task records about itself (SPEC-001 §3 rules 12-22). */
public sealed interface TaskEvent {

  @TypeName("task-queued")
  record Queued(String docId, int fromPage, int toPage, String digest) implements TaskEvent {}

  @TypeName("attempt-started")
  record AttemptStarted(int retryCount) implements TaskEvent {}

  @TypeName("progress-reported")
  record ProgressReported(double progress, String msg) implements TaskEvent {}

  /** Rule 14: digest-matched reuse — no chunk/embed/index stages run. */
  @TypeName("reused")
  record Reused(String docId, List<Chunk> chunks) implements TaskEvent {}

  /** Rules 20-22: the index stage's chunks, written atomically with the DONE transition. */
  @TypeName("indexed")
  record Indexed(String docId, List<Chunk> chunks) implements TaskEvent {}

  /** Rule 16: retryCount reached 3 on this dispatch — abandoned rather than processed. */
  @TypeName("abandoned")
  record Abandoned(String msg) implements TaskEvent {}

  @TypeName("task-failed")
  record Failed(String msg) implements TaskEvent {}

  @TypeName("task-canceled")
  record Canceled() implements TaskEvent {}
}
