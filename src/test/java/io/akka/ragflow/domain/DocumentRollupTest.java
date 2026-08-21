package io.akka.ragflow.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 rules 17-19, question-log row 11. */
class DocumentRollupTest {

  private static TaskSummary task(String id, double progress, TaskStatus status) {
    return new TaskSummary(id, 0, 0, "d", status, progress, 0, 0);
  }

  @Test
  void progressIsTheMeanOfEveryTasksProgress() {
    var result =
        DocumentRollup.of(
            List.of(task("t1", 0.4, TaskStatus.RUNNING), task("t2", 0.6, TaskStatus.RUNNING)),
            DocumentStatus.RUNNING, 0.0);
    assertThat(result.progress()).isEqualTo(0.5);
    assertThat(result.status()).isEqualTo(DocumentStatus.RUNNING);
  }

  @Test
  void finishedWithNoFailureIsDone() {
    var result =
        DocumentRollup.of(
            List.of(task("t1", 1.0, TaskStatus.DONE), task("t2", 1.0, TaskStatus.DONE)),
            DocumentStatus.RUNNING, 0.5);
    assertThat(result.status()).isEqualTo(DocumentStatus.DONE);
    assertThat(result.progress()).isEqualTo(1.0);
  }

  @Test
  void finishedWithAnyFailureIsFail() {
    var result =
        DocumentRollup.of(
            List.of(task("t1", 1.0, TaskStatus.DONE), task("t2", -1.0, TaskStatus.FAIL)),
            DocumentStatus.RUNNING, 0.5);
    assertThat(result.status()).isEqualTo(DocumentStatus.FAIL);
    assertThat(result.progress()).isEqualTo(-1.0);
  }

  @Test
  void aFailedTaskContributesZeroToTheMeanWhileStillRunning() {
    var result =
        DocumentRollup.of(
            List.of(task("t1", -1.0, TaskStatus.FAIL), task("t2", 0.3, TaskStatus.RUNNING)),
            DocumentStatus.RUNNING, 0.0);
    assertThat(result.status()).isEqualTo(DocumentStatus.RUNNING);
    assertThat(result.progress()).isEqualTo(0.15);
  }

  @Test
  void aCanceledDocumentIsNeverOverwrittenByRollup() {
    var result =
        DocumentRollup.of(
            List.of(task("t1", 1.0, TaskStatus.DONE)), DocumentStatus.CANCEL, 0.3);
    assertThat(result.status()).isEqualTo(DocumentStatus.CANCEL);
    assertThat(result.progress()).isEqualTo(0.3);
  }

  @Test
  void noTasksIsUnstart() {
    var result = DocumentRollup.of(List.of(), DocumentStatus.UNSTART, 0.0);
    assertThat(result.status()).isEqualTo(DocumentStatus.UNSTART);
  }
}
