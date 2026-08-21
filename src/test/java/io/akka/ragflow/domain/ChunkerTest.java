package io.akka.ragflow.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.akka.ragflow.domain.Chunker.ChunkDraft;
import io.akka.ragflow.domain.Chunker.Section;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 §3 rules 5-11, 18. Every expected chunk boundary and token count here was produced by
 * running the real {@code rag.nlp.naive_merge} against the same input (question-log rows 1-4,
 * {@code /tmp/verify_naive_merge.py}), not derived from reading this port's own code.
 */
class ChunkerTest {

  private static ParserConfig config(int chunkTokenNum, String delimiter, int overlap) {
    return new ParserConfig(chunkTokenNum, delimiter, overlap, MergeStrategy.OVER_CAP, 12);
  }

  // question-log row 1
  @Test
  void overCapClosesAChunkAfterTheParagraphThatTipsItOver() {
    String text =
        "Alpha bravo charlie delta.\nEcho foxtrot golf hotel.\nIndia juliet kilo lima.\nMike november oscar papa.\n";
    List<ChunkDraft> chunks = Chunker.naiveMerge(List.of(new Section(text, null)), config(8, "\n", 0), false);

    assertThat(chunks).hasSize(3);
    assertThat(chunks.get(0).content()).isEqualTo("\nAlpha bravo charlie delta.\nEcho foxtrot golf hotel.");
    assertThat(chunks.get(0).tokenNum()).isEqualTo(15);
    assertThat(chunks.get(1).content()).isEqualTo("\nIndia juliet kilo lima.");
    assertThat(chunks.get(1).tokenNum()).isEqualTo(9);
    assertThat(chunks.get(2).content()).isEqualTo("\nMike november oscar papa.");
    assertThat(chunks.get(2).tokenNum()).isEqualTo(8);
  }

  // question-log row 2
  @Test
  void overlapUnconditionallyPrependsTheTailOfThePreviousChunkEvenPastTheCap() {
    String text =
        "Alpha bravo charlie delta.\nEcho foxtrot golf hotel.\nIndia juliet kilo lima.\nMike november oscar papa.\n";
    List<ChunkDraft> chunks = Chunker.naiveMerge(List.of(new Section(text, null)), config(8, "\n", 30), false);

    assertThat(chunks).hasSize(4);
    assertThat(chunks.get(0).content()).isEqualTo("\nAlpha bravo charlie delta.");
    assertThat(chunks.get(0).tokenNum()).isEqualTo(8);
    assertThat(chunks.get(1).content()).isEqualTo("ie delta.\nEcho foxtrot golf hotel.");
    assertThat(chunks.get(1).tokenNum()).isEqualTo(10);
    assertThat(chunks.get(2).content()).isEqualTo("golf hotel.\nIndia juliet kilo lima.");
    assertThat(chunks.get(2).tokenNum()).isEqualTo(12);
    assertThat(chunks.get(3).content()).isEqualTo(" kilo lima.\nMike november oscar papa.");
    assertThat(chunks.get(3).tokenNum()).isEqualTo(12);
  }

  // question-log row 3
  @Test
  void aBacktickDelimiterBypassesTheTokenBudgetEntirelyAndSkipsOverlap() {
    String text =
        "Para one is short.\n\nPara two is also fairly short but a bit longer than one.\n\nPara three.";
    List<ChunkDraft> chunks =
        Chunker.naiveMerge(List.of(new Section(text, null)), config(8, "`\n\n`", 50), false);

    assertThat(chunks).hasSize(3);
    assertThat(chunks.get(0).content()).isEqualTo("\nPara one is short.");
    assertThat(chunks.get(0).tokenNum()).isEqualTo(6);
    assertThat(chunks.get(1).content())
        .isEqualTo("\nPara two is also fairly short but a bit longer than one.");
    assertThat(chunks.get(1).tokenNum()).isEqualTo(14);
    assertThat(chunks.get(2).content()).isEqualTo("\nPara three.");
    assertThat(chunks.get(2).tokenNum()).isEqualTo(4);
  }

  // question-log row 4
  @Test
  void aParagraphOverTheCapStandsAloneAndIsNeverSplit() {
    String longPara = "word ".repeat(50);
    String text = "short one.\n" + longPara + "\nshort two.\n";
    List<ChunkDraft> chunks = Chunker.naiveMerge(List.of(new Section(text, null)), config(8, "\n", 0), false);

    assertThat(chunks).hasSize(3);
    assertThat(chunks.get(0).content()).isEqualTo("\nshort one.");
    assertThat(chunks.get(1).tokenNum()).isEqualTo(52);
    assertThat(chunks.get(1).content()).startsWith("\nword word word");
    assertThat(chunks.get(2).content()).isEqualTo("\nshort two.");
  }

  // question-log row 18 — read only, not run against the source; UNDER_CAP is the source's
  // documented alternative strategy, never exercised by any of its own callers.
  @Test
  void underCapNeverOverflowsTheCap() {
    String text = "one two three\nfour five six\nseven eight nine ten eleven\n";
    List<ChunkDraft> chunks =
        Chunker.naiveMerge(
            List.of(new Section(text, null)),
            new ParserConfig(8, "\n", 0, MergeStrategy.UNDER_CAP, 12),
            false);

    for (ChunkDraft c : chunks) {
      assertThat(c.tokenNum()).isLessThanOrEqualTo(8);
    }
  }

  @Test
  void chunkOrderMatchesSourceOrder() {
    String text = "one\ntwo\nthree\nfour\nfive\n";
    List<ChunkDraft> chunks =
        Chunker.naiveMerge(List.of(new Section(text, null)), config(1000, "\n", 0), false);
    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0).content()).isEqualTo("\none\ntwo\nthree\nfour\nfive");
  }

  @Test
  void delimiterTextNeverAppearsInsideAChunk() {
    String text = "a|b|c";
    List<ChunkDraft> chunks = Chunker.naiveMerge(List.of(new Section(text, null)), config(1000, "|", 0), false);
    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0).content()).doesNotContain("|");
  }

  @Test
  void pdfPagesCarryThroughToTheirChunks() {
    List<Section> sections = List.of(new Section("alpha", 1), new Section("beta", 2));
    List<ChunkDraft> chunks = Chunker.naiveMerge(sections, config(1000, "\n", 0), false);
    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0).pages()).containsExactly(1, 2);
  }
}
