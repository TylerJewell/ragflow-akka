package io.akka.ragflow.domain;

import java.util.List;

/** Multi-field payloads for {@code TaskEntity} methods. */
public final class TaskCommand {

  private TaskCommand() {}

  public record Queue(String docId, int fromPage, int toPage, String digest) {}

  public record ReportProgress(double progress, String msg) {}

  public record Reuse(List<Chunk> chunks) {}

  public record Index(List<Chunk> chunks) {}

  public record Fail(String msg) {}
}
