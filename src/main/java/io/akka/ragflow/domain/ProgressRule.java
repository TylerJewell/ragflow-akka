package io.akka.ragflow.domain;

/**
 * The monotonic progress-update rule (SPEC-001 §3 rule 15), ported from {@code
 * TaskService.update_progress}'s SQL `WHERE` clause (question-log row 12, {@code
 * api/db/services/task_service.py:407-415}).
 *
 * <p>A value may always move to {@code >= 1.0} (recovery from any prior state, including
 * failure). Otherwise it may move only while the current value is not already {@code -1.0}, and
 * only to {@code -1.0} or to a value strictly greater than the current one.
 */
public final class ProgressRule {

  private ProgressRule() {}

  public static boolean accepts(double current, double next) {
    if (next >= 1.0) {
      return true;
    }
    return current != -1.0 && (next == -1.0 || next > current);
  }
}
