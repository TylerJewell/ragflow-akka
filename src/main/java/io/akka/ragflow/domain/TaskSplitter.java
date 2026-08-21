package io.akka.ragflow.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * How a document becomes one or more task page ranges (SPEC-001 §3 rule 12), ported from {@code
 * queue_tasks} in {@code api/db/services/task_service.py:462-502} (question-log row 8).
 *
 * <p>Only the branches this port's scope keeps: PDF (page-windowed) and TEXT/MARKDOWN (a single
 * whole-document task). The source's {@code table} (row-windowed) and {@code paper}/{@code
 * one}/{@code knowledge_graph} (larger or unbounded windows) branches are out of scope (SPEC-001
 * §1) because their parsers are.
 */
public final class TaskSplitter {

  /**
   * A task's page range. {@code fromPage}/{@code toPage} are both {@code 0} for a whole-document
   * task (TEXT/MARKDOWN) — there is no page concept to window over.
   */
  public record PageRange(int fromPage, int toPage) {}

  private TaskSplitter() {}

  public static List<PageRange> split(FileType fileType, int totalPages, int taskPageSize) {
    if (fileType != FileType.PDF) {
      return List.of(new PageRange(0, 0));
    }
    if (totalPages <= 0) {
      return List.of(new PageRange(0, 0));
    }
    List<PageRange> ranges = new ArrayList<>();
    for (int p = 0; p < totalPages; p += taskPageSize) {
      ranges.add(new PageRange(p, Math.min(p + taskPageSize, totalPages)));
    }
    return ranges;
  }
}
