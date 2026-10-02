package com.ohmytradeagent.orchestrator.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExitCueMatcherTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        // Every prod_real classifier promotion that had no keyword match (2026-08 → 2026-10).
        "alright not letting it go red",
        "alright I am not gonna hold through this today I cut the rest, Will get back in",
        "alright not letting these go red again.",
        "gotta dump these ones just not enough time left.",
        "bears can't finish taking the W. Don't want to see it go red again",
        "STOPPED on the runners",
      })
  void exitTalk_mentionsExit(String tail) {
    assertThat(ExitCueMatcher.mentionsExit(tail)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"BANG", "🚀", "nice", "haircut", "without a doubt", "", "  "})
  void noExitWord_doesNotMentionExit(String tail) {
    assertThat(ExitCueMatcher.mentionsExit(tail)).isFalse();
  }

  @Test
  void nullTail_doesNotMentionExit() {
    assertThat(ExitCueMatcher.mentionsExit(null)).isFalse();
  }
}
