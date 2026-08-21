package io.akka.ragflow.domain;

/**
 * What a {@code Document} knows about one of its tasks — enough to roll up progress (rules 17-19)
 * and to decide reuse (rule 14) without holding the task's actual chunk content.
 */
public record TaskSummary(
    String taskId,
    int fromPage,
    int toPage,
    String digest,
    TaskStatus status,
    double progress,
    int chunkCount,
    int tokenCount) {

  public TaskSummary withProgress(TaskStatus status, double progress, int chunkCount, int tokenCount) {
    return new TaskSummary(taskId, fromPage, toPage, digest, status, progress, chunkCount, tokenCount);
  }
}
