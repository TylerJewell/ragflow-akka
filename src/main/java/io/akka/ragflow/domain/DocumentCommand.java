package io.akka.ragflow.domain;

import java.util.List;

/** Multi-field payloads for {@code DocumentEntity} methods. */
public final class DocumentCommand {

  private DocumentCommand() {}

  public record Create(
      String kbId, String name, FileType fileType, List<String> sections, ParserConfig parserConfig) {}

  public record ReportTaskProgress(
      String taskId,
      int fromPage,
      int toPage,
      String digest,
      TaskStatus status,
      double progress,
      int chunkCount,
      int tokenCount) {}
}
