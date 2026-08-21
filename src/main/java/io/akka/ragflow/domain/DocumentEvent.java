package io.akka.ragflow.domain;

import akka.javasdk.annotations.TypeName;
import java.util.List;

/** What a document records about itself (SPEC-001 §3 rules 12-19). */
public sealed interface DocumentEvent {

  @TypeName("document-created")
  record Created(
      String kbId, String name, FileType fileType, List<String> sections, ParserConfig parserConfig)
      implements DocumentEvent {}

  @TypeName("parse-started")
  record ParseStarted(int generation, List<TaskDescriptor> tasks) implements DocumentEvent {}

  @TypeName("task-progress-reported")
  record TaskProgressReported(
      String taskId,
      int fromPage,
      int toPage,
      String digest,
      TaskStatus status,
      double progress,
      int chunkCount,
      int tokenCount)
      implements DocumentEvent {}

  @TypeName("document-canceled")
  record Canceled() implements DocumentEvent {}
}
