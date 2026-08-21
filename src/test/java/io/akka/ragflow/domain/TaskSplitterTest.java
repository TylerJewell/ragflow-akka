package io.akka.ragflow.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.akka.ragflow.domain.TaskSplitter.PageRange;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 rule 12, question-log row 8. */
class TaskSplitterTest {

  @Test
  void textDocumentsAreASingleWholeDocumentTask() {
    assertThat(TaskSplitter.split(FileType.TEXT, 0, 12)).containsExactly(new PageRange(0, 0));
  }

  @Test
  void markdownDocumentsAreASingleWholeDocumentTask() {
    assertThat(TaskSplitter.split(FileType.MARKDOWN, 1, 12)).containsExactly(new PageRange(0, 0));
  }

  @Test
  void pdfDocumentsAreSplitIntoNonOverlappingPageWindows() {
    List<PageRange> ranges = TaskSplitter.split(FileType.PDF, 30, 12);
    assertThat(ranges).containsExactly(new PageRange(0, 12), new PageRange(12, 24), new PageRange(24, 30));
  }

  @Test
  void everyPageIsCoveredExactlyOnce() {
    List<PageRange> ranges = TaskSplitter.split(FileType.PDF, 25, 12);
    int covered = ranges.stream().mapToInt(r -> r.toPage() - r.fromPage()).sum();
    assertThat(covered).isEqualTo(25);
    for (int i = 1; i < ranges.size(); i++) {
      assertThat(ranges.get(i).fromPage()).isEqualTo(ranges.get(i - 1).toPage());
    }
  }

  @Test
  void aPdfThatExactlyFillsOneWindowIsOneTask() {
    assertThat(TaskSplitter.split(FileType.PDF, 12, 12)).containsExactly(new PageRange(0, 12));
  }

  @Test
  void aZeroPagePdfIsOneEmptyRangeRatherThanNoTasks() {
    assertThat(TaskSplitter.split(FileType.PDF, 0, 12)).containsExactly(new PageRange(0, 0));
  }
}
