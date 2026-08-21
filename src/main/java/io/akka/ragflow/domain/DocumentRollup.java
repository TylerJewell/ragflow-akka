package io.akka.ragflow.domain;

import java.util.List;

/**
 * The document-level progress rollup (SPEC-001 §3 rules 17-19), ported from {@code
 * DocumentService._sync_progress} (question-log row 11, {@code
 * api/db/services/document_service.py:1088-1152}).
 */
public final class DocumentRollup {

  public record Result(DocumentStatus status, double progress) {}

  private DocumentRollup() {}

  /**
   * {@code currentStatus} is read first because rule 19 makes {@code CANCEL} terminal: a canceled
   * document's status and progress are returned unchanged regardless of task state.
   */
  public static Result of(List<TaskSummary> tasks, DocumentStatus currentStatus, double currentProgress) {
    if (currentStatus == DocumentStatus.CANCEL) {
      return new Result(currentStatus, currentProgress);
    }
    if (tasks.isEmpty()) {
      return new Result(DocumentStatus.UNSTART, 0.0);
    }
    double sum = 0.0;
    boolean finished = true;
    int bad = 0;
    for (TaskSummary t : tasks) {
      double p = t.progress();
      if (p >= 0 && p < 1) {
        finished = false;
      }
      if (p == -1) {
        bad++;
      }
      sum += p >= 0 ? p : 0;
    }
    double prg = sum / tasks.size();
    if (finished && bad > 0) {
      return new Result(DocumentStatus.FAIL, -1.0);
    }
    if (finished) {
      return new Result(DocumentStatus.DONE, 1.0);
    }
    return new Result(DocumentStatus.RUNNING, prg);
  }
}
