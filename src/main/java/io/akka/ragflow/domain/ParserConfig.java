package io.akka.ragflow.domain;

/**
 * Chunking configuration for one document (SPEC-001 §2). Defaults match the source's API-schema
 * defaults (question-log row 14: {@code api/utils/validation_utils.py:453-466}).
 */
public record ParserConfig(
    int chunkTokenNum, String delimiter, int overlappedPercent, MergeStrategy strategy, int taskPageSize) {

  public static ParserConfig defaults() {
    return new ParserConfig(512, "\n", 0, MergeStrategy.OVER_CAP, 12);
  }

  /** The hash input for {@link TaskDigest} — every field, order-independent by name. */
  public String digestFields() {
    return "chunkTokenNum=" + chunkTokenNum
        + ";delimiter=" + delimiter
        + ";overlappedPercent=" + overlappedPercent
        + ";strategy=" + strategy
        + ";taskPageSize=" + taskPageSize;
  }
}
