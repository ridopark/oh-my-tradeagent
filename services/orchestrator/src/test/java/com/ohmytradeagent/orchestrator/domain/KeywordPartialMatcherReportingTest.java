package com.ohmytradeagent.orchestrator.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * PLAN-2026-07-01-unrecognized-stc-tail-alert: the match-reporting helper {@link
 * KeywordPartialMatcher#matchReporting} exposes the winning key alongside the fraction so the
 * observability alerter can tell "matched a keyword" from "fell through to default". The existing
 * {@link KeywordPartialMatcher#match} numeric behavior must stay byte-identical (delegation).
 */
class KeywordPartialMatcherReportingTest {

  private static final double DEFAULT = 0.3;

  @Test
  void matchReport_returnsWinningKey_whenMatched() {
    Map<String, Double> fractions = new LinkedHashMap<>();
    fractions.put("trim", 0.25);

    KeywordPartialMatcher.MatchResult r =
        KeywordPartialMatcher.matchReporting("trimming into strength", fractions, DEFAULT);

    assertThat(r.matchedKey()).contains("trim");
    assertThat(r.fraction()).isEqualTo(0.25, within(1e-9));
  }

  @Test
  void matchReport_returnsLongestKey_whenMultipleMatch() {
    Map<String, Double> fractions = new LinkedHashMap<>();
    fractions.put("half", 0.5);
    fractions.put("out", 1.0);
    fractions.put("half out", 0.5);

    KeywordPartialMatcher.MatchResult r =
        KeywordPartialMatcher.matchReporting("half out", fractions, DEFAULT);

    // Longest-key-wins: "half out" (8) beats "half" (4) and "out" (3).
    assertThat(r.matchedKey()).contains("half out");
    assertThat(r.fraction()).isEqualTo(0.5, within(1e-9));
  }

  @Test
  void matchReport_isEmpty_whenNoKeyMatches() {
    Map<String, Double> fractions = Map.of("close", 1.0, "trim", 0.25);

    KeywordPartialMatcher.MatchResult r =
        KeywordPartialMatcher.matchReporting("yolo it", fractions, DEFAULT);

    assertThat(r.matchedKey()).isEmpty();
    assertThat(r.fraction()).isEqualTo(DEFAULT, within(1e-9));
  }

  @Test
  void matchReport_isEmpty_whenTailIsEmpty() {
    Map<String, Double> fractions = Map.of("out", 1.0);

    KeywordPartialMatcher.MatchResult r =
        KeywordPartialMatcher.matchReporting("", fractions, DEFAULT);

    assertThat(r.matchedKey()).isEmpty();
    assertThat(r.fraction()).isEqualTo(DEFAULT, within(1e-9));
  }

  @Test
  void matchReport_isEmpty_whenTailIsNull() {
    Map<String, Double> fractions = Map.of("out", 1.0);

    KeywordPartialMatcher.MatchResult r =
        KeywordPartialMatcher.matchReporting(null, fractions, DEFAULT);

    assertThat(r.matchedKey()).isEmpty();
    assertThat(r.fraction()).isEqualTo(DEFAULT, within(1e-9));
  }

  @Test
  void matchReport_isEmpty_whenFractionsMapIsEmpty() {
    KeywordPartialMatcher.MatchResult r =
        KeywordPartialMatcher.matchReporting("half out", Map.of(), DEFAULT);

    assertThat(r.matchedKey()).isEmpty();
    assertThat(r.fraction()).isEqualTo(DEFAULT, within(1e-9));
  }

  @Test
  void matchReport_isEmpty_whenFractionsMapIsNull() {
    KeywordPartialMatcher.MatchResult r =
        KeywordPartialMatcher.matchReporting("half out", null, DEFAULT);

    assertThat(r.matchedKey()).isEmpty();
    assertThat(r.fraction()).isEqualTo(DEFAULT, within(1e-9));
  }

  @Test
  void matchReport_matchedKeyIsLowercased_matchingMatchSemantics() {
    Map<String, Double> fractions = Map.of("OUT", 1.0);

    KeywordPartialMatcher.MatchResult r =
        KeywordPartialMatcher.matchReporting("all OUT now", fractions, DEFAULT);

    // The winning key is reported lowercased (the matcher lowercases keys internally).
    assertThat(r.matchedKey()).contains("out");
    assertThat(r.fraction()).isEqualTo(1.0, within(1e-9));
  }

  @Test
  void match_delegatesToMatchReport_numericBehaviorUnchanged() {
    Map<String, Double> fractions = new LinkedHashMap<>();
    fractions.put("half", 0.5);
    fractions.put("out", 1.0);
    fractions.put("half out", 0.5);

    // match(...) must return exactly matchReporting(...).fraction() for the same inputs.
    assertThat(KeywordPartialMatcher.match("half out", fractions, DEFAULT))
        .isEqualTo(KeywordPartialMatcher.matchReporting("half out", fractions, DEFAULT).fraction());
    assertThat(KeywordPartialMatcher.match("yolo it", fractions, DEFAULT))
        .isEqualTo(KeywordPartialMatcher.matchReporting("yolo it", fractions, DEFAULT).fraction());
    assertThat(KeywordPartialMatcher.match(null, fractions, DEFAULT))
        .isEqualTo(KeywordPartialMatcher.matchReporting(null, fractions, DEFAULT).fraction());
  }
}
