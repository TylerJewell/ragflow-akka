package io.akka.ragflow.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** SPEC-001 §3 rule 15, question-log row 12. */
class ProgressRuleTest {

  @Test
  void anyValueMayMoveToOneOrAbove() {
    assertThat(ProgressRule.accepts(0.5, 1.0)).isTrue();
    assertThat(ProgressRule.accepts(-1.0, 1.0)).isTrue(); // recovery from failure
  }

  @Test
  void aGreaterRunningValueIsAccepted() {
    assertThat(ProgressRule.accepts(0.2, 0.5)).isTrue();
  }

  @Test
  void aLesserOrEqualRunningValueIsRejected() {
    assertThat(ProgressRule.accepts(0.5, 0.5)).isFalse();
    assertThat(ProgressRule.accepts(0.5, 0.2)).isFalse();
  }

  @Test
  void movingToFailedIsAcceptedFromARunningValue() {
    assertThat(ProgressRule.accepts(0.5, -1.0)).isTrue();
  }

  @Test
  void onceFailedOnlyRecoveryToOneOrAboveIsAccepted() {
    assertThat(ProgressRule.accepts(-1.0, 0.3)).isFalse();
    assertThat(ProgressRule.accepts(-1.0, -1.0)).isFalse();
    assertThat(ProgressRule.accepts(-1.0, 1.0)).isTrue();
  }
}
