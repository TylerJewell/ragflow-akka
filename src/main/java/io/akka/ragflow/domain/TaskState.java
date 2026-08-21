package io.akka.ragflow.domain;

import java.util.List;

/** SPEC-001 §2 Task. */
public record TaskState(
    String taskId,
    String docId,
    int fromPage,
    int toPage,
    String digest,
    TaskStatus status,
    double progress,
    String progressMsg,
    int retryCount,
    List<Chunk> chunks) {

  public static TaskState empty(String taskId) {
    return new TaskState(taskId, null, 0, 0, null, null, 0.0, "", 0, List.of());
  }

  public boolean isQueued() {
    return docId != null;
  }
}
