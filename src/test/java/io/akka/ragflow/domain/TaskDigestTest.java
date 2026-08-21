package io.akka.ragflow.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** SPEC-001 §3 rule 13, question-log row 9. */
class TaskDigestTest {

  @Test
  void sameConfigAndRangeAlwaysProducesTheSameDigest() {
    ParserConfig config = ParserConfig.defaults();
    assertThat(TaskDigest.of(config, "doc-1", 0, 12)).isEqualTo(TaskDigest.of(config, "doc-1", 0, 12));
  }

  @Test
  void aChangeToChunkTokenNumChangesTheDigest() {
    ParserConfig a = ParserConfig.defaults();
    ParserConfig b = new ParserConfig(a.chunkTokenNum() + 1, a.delimiter(), a.overlappedPercent(), a.strategy(), a.taskPageSize());
    assertThat(TaskDigest.of(a, "doc-1", 0, 12)).isNotEqualTo(TaskDigest.of(b, "doc-1", 0, 12));
  }

  @Test
  void aChangeToDelimiterChangesTheDigest() {
    ParserConfig a = ParserConfig.defaults();
    ParserConfig b = new ParserConfig(a.chunkTokenNum(), "`\n\n`", a.overlappedPercent(), a.strategy(), a.taskPageSize());
    assertThat(TaskDigest.of(a, "doc-1", 0, 12)).isNotEqualTo(TaskDigest.of(b, "doc-1", 0, 12));
  }

  @Test
  void aChangeToOverlapChangesTheDigest() {
    ParserConfig a = ParserConfig.defaults();
    ParserConfig b = new ParserConfig(a.chunkTokenNum(), a.delimiter(), 30, a.strategy(), a.taskPageSize());
    assertThat(TaskDigest.of(a, "doc-1", 0, 12)).isNotEqualTo(TaskDigest.of(b, "doc-1", 0, 12));
  }

  @Test
  void aChangeToStrategyChangesTheDigest() {
    ParserConfig a = ParserConfig.defaults();
    ParserConfig b = new ParserConfig(a.chunkTokenNum(), a.delimiter(), a.overlappedPercent(), MergeStrategy.UNDER_CAP, a.taskPageSize());
    assertThat(TaskDigest.of(a, "doc-1", 0, 12)).isNotEqualTo(TaskDigest.of(b, "doc-1", 0, 12));
  }

  @Test
  void aChangeToTaskPageSizeChangesTheDigest() {
    ParserConfig a = ParserConfig.defaults();
    ParserConfig b = new ParserConfig(a.chunkTokenNum(), a.delimiter(), a.overlappedPercent(), a.strategy(), a.taskPageSize() + 1);
    assertThat(TaskDigest.of(a, "doc-1", 0, 12)).isNotEqualTo(TaskDigest.of(b, "doc-1", 0, 12));
  }

  @Test
  void aDifferentPageRangeChangesTheDigest() {
    ParserConfig config = ParserConfig.defaults();
    assertThat(TaskDigest.of(config, "doc-1", 0, 12)).isNotEqualTo(TaskDigest.of(config, "doc-1", 12, 24));
  }

  @Test
  void aDifferentDocumentChangesTheDigest() {
    ParserConfig config = ParserConfig.defaults();
    assertThat(TaskDigest.of(config, "doc-1", 0, 12)).isNotEqualTo(TaskDigest.of(config, "doc-2", 0, 12));
  }
}
