package io.akka.ragflow.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.akka.ragflow.domain.Chunker.Section;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Times {@link Chunker#naiveMerge} in-process, with no HTTP around it — the other half of
 * {@code ragflow-port/bench/}'s speed comparison. {@code bench/port_answers.py} necessarily times
 * a full HTTP round trip (JSON marshal, network stack, entity dispatch) because that is the only
 * surface {@code BenchEndpoint} exposes; this test isolates the JVM-side computation itself, the
 * side of the comparison an HTTP client can never see. Both numbers are quoted in
 * {@code bench/REPORT.md} rather than only the HTTP one, per PIPELINE.md §e's rule that a ratio
 * is only about the capability if the capability is most of what was timed.
 */
class ChunkerBenchTest {

  private record Workload(String id, String text, int chunkTokenNum, String delimiter, int overlap) {}

  // Same eight workloads as ragflow-port/bench/workloads.json, by id.
  private static final List<Workload> WORKLOADS =
      List.of(
          new Workload(
              "basic_over_cap",
              "Alpha bravo charlie delta.\nEcho foxtrot golf hotel.\nIndia juliet kilo lima.\nMike november oscar papa.\n",
              8, "\n", 0),
          new Workload(
              "with_overlap_30",
              "Alpha bravo charlie delta.\nEcho foxtrot golf hotel.\nIndia juliet kilo lima.\nMike november oscar papa.\n",
              8, "\n", 30),
          new Workload(
              "custom_delimiter_bypasses_budget",
              "Para one is short.\n\nPara two is also fairly short but a bit longer than one.\n\nPara three.",
              8, "`\n\n`", 0),
          new Workload(
              "oversize_paragraph_stands_alone",
              "short one.\n" + "word ".repeat(50).stripTrailing() + "\nshort two.\n",
              8, "\n", 0),
          new Workload(
              "crlf_normalization",
              "First line of the report.\r\nSecond line follows it.\r\nThird line closes it out.\r\n",
              6, "\n", 0),
          new Workload(
              "multi_char_delimiter",
              "Section one covers the introduction and scope.---Section two covers the method used throughout.---Section three covers what was found and why it matters.",
              1000, "`---`", 0),
          new Workload(
              "many_small_paragraphs_with_overlap",
              "one two\nthree four\nfive six\nseven eight\nnine ten\neleven twelve\nthirteen fourteen\nfifteen sixteen\nseventeen eighteen\nnineteen twenty\n",
              20, "\n", 15),
          new Workload(
              "empty_delimiter_whole_text_one_paragraph",
              "This whole block of text has no delimiter characters at all so it must stay one single paragraph before any merge grouping happens regardless of how large the configured chunk token budget is set to be for this particular workload.",
              5, "", 0));

  private static final int ROUNDS = 50;

  @Test
  void timeEachWorkloadInProcess() {
    System.out.println("\nworkload,chunks,us_per_call_in_process");
    for (Workload w : WORKLOADS) {
      var config = new ParserConfig(w.chunkTokenNum(), w.delimiter(), w.overlap(), MergeStrategy.OVER_CAP, 12);
      var section = List.of(new Section(w.text(), null));

      var chunks = Chunker.naiveMerge(section, config, false);
      assertThat(chunks).isNotEmpty();

      long start = System.nanoTime();
      for (int i = 0; i < ROUNDS; i++) {
        Chunker.naiveMerge(section, config, false);
      }
      double usPerCall = (System.nanoTime() - start) / 1000.0 / ROUNDS;
      System.out.printf("%s,%d,%.1f%n", w.id(), chunks.size(), usPerCall);
    }
  }
}
