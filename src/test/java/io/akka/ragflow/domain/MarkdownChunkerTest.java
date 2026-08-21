package io.akka.ragflow.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.akka.ragflow.domain.Chunker.ChunkDraft;
import io.akka.ragflow.domain.Chunker.Section;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 §3 rule 10 — a short Markdown heading is never its own chunk, ported from {@code
 * _is_short_header} (question-log row 13, {@code rag/app/naive.py:67-86}).
 */
class MarkdownChunkerTest {

  // A cap of 1 forces every individual paragraph to stand alone (rule 8), which is exactly what
  // makes the difference between the markdown and plain paths observable: only the markdown path
  // pre-merges the heading into the paragraph that follows it, before that standalone rule runs.
  private static ParserConfig tinyCapConfig() {
    return new ParserConfig(1, "\n", 0, MergeStrategy.OVER_CAP, 12);
  }

  @Test
  void aShortHeadingIsForceMergedIntoTheFollowingParagraph() {
    String text = "# Title\nBody paragraph one is normal length text here.\n";
    Section section = new Section(text, null);

    List<ChunkDraft> withoutRule = Chunker.naiveMerge(List.of(section), tinyCapConfig(), false);
    List<ChunkDraft> withRule = Chunker.naiveMerge(List.of(section), tinyCapConfig(), true);

    assertThat(withoutRule).hasSize(2); // the heading stands alone without the markdown rule.
    assertThat(withoutRule.get(0).content()).isEqualTo("\n# Title");

    assertThat(withRule).hasSize(1); // merged into what follows, per rule 10.
    assertThat(withRule.get(0).content()).isEqualTo("\n# Title\nBody paragraph one is normal length text here.");
  }

  @Test
  void aHeadingLineOf50TokensOrMoreIsNotForceMerged() {
    String longHeading = "# " + "word ".repeat(60);
    String text = longHeading + "\nShort body.\n";
    List<ChunkDraft> chunks =
        Chunker.naiveMerge(List.of(new Section(text, null)), tinyCapConfig(), true);

    assertThat(chunks).hasSize(2);
    assertThat(chunks.get(0).content()).startsWith("\n# word word");
  }

  @Test
  void aLineThatIsNotAHeadingIsNeverForceMerged() {
    String text = "Not a heading, just a short line.\nBody paragraph two here.\n";
    List<ChunkDraft> chunks =
        Chunker.naiveMerge(List.of(new Section(text, null)), tinyCapConfig(), true);

    assertThat(chunks).hasSize(2);
  }
}
