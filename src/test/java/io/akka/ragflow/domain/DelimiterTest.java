package io.akka.ragflow.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 rules 1-4, question-log rows 5-6. */
class DelimiterTest {

  @Test
  void bareCharactersAreEachTheirOwnDelimiter() {
    assertThat(Delimiter.parseField("\n!?")).containsExactlyInAnyOrder("\n", "!", "?");
  }

  @Test
  void aBacktickWrappedRunIsOneMultiCharacterDelimiter() {
    assertThat(Delimiter.parseField("`\n\n`")).containsExactly("\n\n");
  }

  @Test
  void bareAndBacktickTokensCombineAndDeduplicate() {
    // "!" appears once bare and the backtick token is distinct — three delimiters out.
    List<String> result = Delimiter.parseField("!`\n\n`!?");
    assertThat(result).containsExactlyInAnyOrder("\n\n", "!", "?");
  }

  @Test
  void resultIsSortedLongestFirstSoAMultiCharDelimiterIsTriedBeforeItsPrefix() {
    List<String> result = Delimiter.parseField("#`##`");
    assertThat(result).containsExactly("##", "#");
  }

  @Test
  void crlfAndStandaloneCrNormalizeToLf() {
    assertThat(Delimiter.parseField("\r\n")).containsExactly("\n");
    assertThat(Delimiter.parseField("\r")).containsExactly("\n");
  }

  @Test
  void hasWrappedDelimiterDetectsAtLeastOneBacktickToken() {
    assertThat(Delimiter.hasWrappedDelimiter("`\n\n`")).isTrue();
    assertThat(Delimiter.hasWrappedDelimiter("\n!?")).isFalse();
    assertThat(Delimiter.hasWrappedDelimiter("")).isFalse();
  }

  @Test
  void emptyFieldParsesToNoDelimiters() {
    assertThat(Delimiter.parseField("")).isEmpty();
    assertThat(Delimiter.parseField(null)).isEmpty();
  }

  @Test
  void compilePatternIsEmptyForNoDelimiters() {
    assertThat(Delimiter.compilePattern(List.of())).isEmpty();
  }
}
