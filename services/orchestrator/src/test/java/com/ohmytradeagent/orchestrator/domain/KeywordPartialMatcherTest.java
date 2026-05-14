package com.ohmytradeagent.orchestrator.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KeywordPartialMatcherTest {

  private static final double DEFAULT = 0.5;

  @Test
  void longestMatchingKeyWins_halfOut() {
    Map<String, Double> fractions = new LinkedHashMap<>();
    fractions.put("half", 0.5);
    fractions.put("out", 1.0);
    fractions.put("half out", 0.5);

    double f = KeywordPartialMatcher.match("half out", fractions, DEFAULT);

    assertThat(f).isEqualTo(0.5, within(1e-9));
  }

  @Test
  void caseInsensitive_outMatchesUppercase() {
    Map<String, Double> fractions = Map.of("out", 1.0, "half", 0.5);

    double f = KeywordPartialMatcher.match("OUT", fractions, DEFAULT);

    assertThat(f).isEqualTo(1.0, within(1e-9));
  }

  @Test
  void nullTail_returnsDefault() {
    Map<String, Double> fractions = Map.of("out", 1.0);

    double f = KeywordPartialMatcher.match(null, fractions, DEFAULT);

    assertThat(f).isEqualTo(DEFAULT, within(1e-9));
  }

  @Test
  void emptyFractionsMap_returnsDefault() {
    double f = KeywordPartialMatcher.match("half out", Map.of(), DEFAULT);

    assertThat(f).isEqualTo(DEFAULT, within(1e-9));
  }

  @Test
  void noMatchingKey_returnsDefault() {
    Map<String, Double> fractions = Map.of("close", 1.0, "trim", 0.25);

    double f = KeywordPartialMatcher.match("some unrelated tail", fractions, DEFAULT);

    assertThat(f).isEqualTo(DEFAULT, within(1e-9));
  }

  @Test
  void trimMatchesAsSubstring_inMixedTail() {
    Map<String, Double> fractions = Map.of("trim", 0.25);

    double f = KeywordPartialMatcher.match("trimming and resting", fractions, DEFAULT);

    assertThat(f).isEqualTo(0.25, within(1e-9));
  }

  @Test
  void shorterKey_wins_whenLongerKeyDoesNotMatch() {
    Map<String, Double> fractions = new LinkedHashMap<>();
    fractions.put("scale", 0.25);
    fractions.put("scale out", 1.0);

    // tail is "scale" only — "scale out" does not match; "scale" does.
    double f = KeywordPartialMatcher.match("scale", fractions, DEFAULT);

    assertThat(f).isEqualTo(0.25, within(1e-9));
  }
}
