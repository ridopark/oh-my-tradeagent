package com.ohmytradeagent.orchestrator.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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

  // ---------------------------------------------------------------------------
  // PLAN-2026-07-20-stc-fraction-keyword-collision: when a tail matches multiple
  // keys mapping to DIFFERENT fractions, resolve to the SMALLEST (conservative)
  // fraction and flag the collision — was longest-key-wins, which full-closed an
  // explicitly-partial signal.
  // ---------------------------------------------------------------------------

  @Test
  void incidentTail_multiFractionMatch_resolvesToSmallest_andFlagsCollision() {
    // Live incident: "STC ... partial. Taking profit as it comes" matched "partial"(0.3) AND
    // "taking profit"(1.0). Longest-key-wins picked 1.0 → full-closed a 50-lot position. The
    // conservative policy picks min(0.3, 1.0) = 0.3 and reports the collision.
    Map<String, Double> fractions = new LinkedHashMap<>();
    fractions.put("partial", 0.3);
    fractions.put("taking profit", 1.0);

    KeywordPartialMatcher.MatchResult r =
        KeywordPartialMatcher.matchReporting("partial. Taking profit as it comes", fractions, 0.3);

    assertThat(r.fraction()).isEqualTo(0.3, within(1e-9));
    assertThat(r.matchedKey()).contains("partial");
    assertThat(r.fractionCollision()).isTrue();
  }

  @Test
  void singleMatch_noCollision() {
    KeywordPartialMatcher.MatchResult r =
        KeywordPartialMatcher.matchReporting("all out", Map.of("out", 1.0), 0.3);

    assertThat(r.fraction()).isEqualTo(1.0, within(1e-9));
    assertThat(r.matchedKey()).contains("out");
    assertThat(r.fractionCollision()).isFalse();
  }

  @Test
  void sameFractionMultiMatch_isNotACollision() {
    Map<String, Double> fractions = new LinkedHashMap<>();
    fractions.put("partial", 0.5);
    fractions.put("trim", 0.5);

    KeywordPartialMatcher.MatchResult r =
        KeywordPartialMatcher.matchReporting("partial trim", fractions, DEFAULT);

    assertThat(r.fraction()).isEqualTo(0.5, within(1e-9));
    assertThat(r.fractionCollision()).isFalse();
  }

  @Test
  void noMatch_defaultFraction_noCollision_emptyKey() {
    KeywordPartialMatcher.MatchResult r =
        KeywordPartialMatcher.matchReporting("nothing relevant", Map.of("out", 1.0), DEFAULT);

    assertThat(r.fraction()).isEqualTo(DEFAULT, within(1e-9));
    assertThat(r.matchedKey()).isEmpty();
    assertThat(r.fractionCollision()).isFalse();
  }

  @Test
  void match_delegatesToMatchReportingFraction() {
    Map<String, Double> fractions = new LinkedHashMap<>();
    fractions.put("partial", 0.3);
    fractions.put("taking profit", 1.0);

    double f = KeywordPartialMatcher.match("partial. Taking profit as it comes", fractions, 0.3);

    assertThat(f)
        .isEqualTo(
            KeywordPartialMatcher.matchReporting(
                    "partial. Taking profit as it comes", fractions, 0.3)
                .fraction(),
            within(1e-9));
  }

  // ---------------------------------------------------------------------------
  // Issue F3: full-close synonyms. Mirrors dev's copytrade-v1.yaml partial_fractions
  // key set (including the new full-close synonyms) so the test reflects real config.
  // The matcher CODE is unchanged; only config + these tests change.
  // ---------------------------------------------------------------------------

  /** The exact partial_fractions key set dev's copytrade-v1.yaml now ships. */
  private static final Map<String, Double> DEV_FRACTIONS = devPartialFractions();

  private static Map<String, Double> devPartialFractions() {
    Map<String, Double> m = new LinkedHashMap<>();
    m.put("out", 1.0);
    m.put("all out", 1.0);
    m.put("close", 1.0);
    m.put("half", 0.5);
    m.put("half out", 0.5);
    m.put("partial", 0.5);
    m.put("third", 0.33);
    m.put("two thirds", 0.67);
    m.put("trim", 0.25);
    // F3 full-close synonyms → 1.0
    m.put("cutting", 1.0);
    m.put("closing", 1.0);
    m.put("closed", 1.0);
    m.put("all the way out", 1.0);
    m.put("stopped out", 1.0);
    m.put("dumped", 1.0);
    m.put("dumping", 1.0);
    return m;
  }

  @Test
  void cuttingBearsSuck_resolvesToFullClose_underNewMap() {
    // The reported defect: "STC DRAM 7/17 60p @ 1.90 cutting. Bears suck" closed only
    // HALF because the tail matched no key and fell to default (0.5). With "cutting"=1.0
    // it now resolves to a full close.
    double f = KeywordPartialMatcher.match("cutting. Bears suck", DEV_FRACTIONS, DEFAULT);

    assertThat(f).isEqualTo(1.0, within(1e-9));
  }

  @Test
  void halfOut_stillResolvesToHalf() {
    // Regression: the longer "half out" (0.5) key still wins over "out" (1.0).
    double f = KeywordPartialMatcher.match("half out", DEV_FRACTIONS, DEFAULT);

    assertThat(f).isEqualTo(0.5, within(1e-9));
  }

  @Test
  void mixedFractionTail_resolvesToSmallestFraction_flagsCollision() {
    // PLAN-2026-07-20: tail contains BOTH a 0.5 key ("half out") and a 1.0 key ("cutting").
    // The conservative policy resolves to the SMALLEST matched fraction => 0.5 and flags the
    // multi-fraction collision. (Pre-2026-07-20 longest-key-wins also landed on 0.5 here by
    // coincidence; this now locks the fraction-based rule.)
    KeywordPartialMatcher.MatchResult r =
        KeywordPartialMatcher.matchReporting("half out, cutting the rest", DEV_FRACTIONS, DEFAULT);

    assertThat(r.fraction()).isEqualTo(0.5, within(1e-9));
    assertThat(r.fractionCollision()).isTrue();
  }

  @Test
  void unqualifiedTail_fallsToDefault() {
    // No keyword present => default still applies (operator kept default_stc_fraction=0.5).
    double f = KeywordPartialMatcher.match("bears suck", DEV_FRACTIONS, DEFAULT);

    assertThat(f).isEqualTo(DEFAULT, within(1e-9));
  }

  @ParameterizedTest
  @CsvSource({
    "closing half, 0.5", // "closing"(1.0) + "half"(0.5) -> min 0.5
    "closed a third, 0.33", // "close"/"closed"(1.0) + "third"(0.33) -> min 0.33
    "cutting half, 0.5", // "cutting"(1.0) + "half"(0.5) -> min 0.5
    "dumped half, 0.5", // "dumped"(1.0) + "half"(0.5) -> min 0.5
    "dumping a third, 0.33", // "dumping"(1.0) + "third"(0.33) -> min 0.33
  })
  void closeVerbPlusQuantity_resolvesToSmallerFraction_afterCollisionFix(
      String tail, double expected) {
    // PLAN-2026-07-20: previously a DOCUMENTED HAZARD — under longest-key-wins a close-VERB
    // synonym longer than the partial-QUANTITY token full-closed a signal the author meant to
    // scale ("closing half" -> "closing"(7) beat "half"(4) -> 1.0). The conservative policy now
    // resolves the multi-fraction collision to the SMALLER fraction, matching author intent, and
    // flags the collision so the operator can verify.
    KeywordPartialMatcher.MatchResult r =
        KeywordPartialMatcher.matchReporting(tail, DEV_FRACTIONS, DEFAULT);
    assertThat(r.fraction()).isEqualTo(expected, within(1e-9));
    assertThat(r.fractionCollision()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "cutting",
        "closing",
        "closed",
        "all the way out",
        "stopped out",
        "dumped",
        "dumping"
      })
  void fullCloseSynonym_resolvesToFullClose_inIsolation(String synonym) {
    // Positive smoke test: every surviving F3 full-close synonym resolves to 1.0 in
    // isolation. Catches a YAML/DEV_FRACTIONS typo (a misspelled key would silently fall
    // to default 0.5).
    assertThat(KeywordPartialMatcher.match(synonym, DEV_FRACTIONS, DEFAULT))
        .isEqualTo(1.0, within(1e-9));
  }
}
