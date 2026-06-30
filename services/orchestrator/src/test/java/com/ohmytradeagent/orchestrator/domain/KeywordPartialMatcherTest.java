package com.ohmytradeagent.orchestrator.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
  void longestKeyWins_collisionGuard() {
    // Tail contains BOTH a 0.5 key ("half out", 8 chars) and a 1.0 key ("cutting",
    // 7 chars). Longest-key-wins => "half out" (8 > 7) => 0.5. Locks the documented
    // behavior so a future map edit can't silently flip a mixed-phrase outcome.
    double f = KeywordPartialMatcher.match("half out, cutting the rest", DEV_FRACTIONS, DEFAULT);

    assertThat(f).isEqualTo(0.5, within(1e-9));
  }

  @Test
  void unqualifiedTail_fallsToDefault() {
    // No keyword present => default still applies (operator kept default_stc_fraction=0.5).
    double f = KeywordPartialMatcher.match("bears suck", DEV_FRACTIONS, DEFAULT);

    assertThat(f).isEqualTo(DEFAULT, within(1e-9));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "closing half", // "closing"(7) > "half"(4)
        "closed a third", // "closed"(6) > "third"(5)
        "cutting half", // "cutting"(7) > "half"(4)
        "dumped half", // "dumped"(6) > "half"(4)
        "dumping a third", // "dumping"(7) > "third"(5)
      })
  void closeVerbPlusQuantity_resolvesFullClose_documentedHazard(String tail) {
    // DOCUMENTED HAZARD (not a regression of this change — it locks the behavior so a
    // future map edit can't change it silently). Under longest-key-wins substring
    // matching, a close-VERB synonym that is longer than the partial-QUANTITY token beats
    // it: e.g. "closing half" contains "closing"(7) and "half"(4) -> "closing" wins -> 1.0
    // (full close), even though the author likely meant half. This is inherent to
    // substring+longest-wins; resolving it cleanly needs grammar-aware parsing, out of
    // scope for F3 (config-only). The operator keeps these full-close synonyms; this test
    // makes the accepted trade-off explicit rather than letting it pass unnoticed.
    assertThat(KeywordPartialMatcher.match(tail, DEV_FRACTIONS, DEFAULT))
        .isEqualTo(1.0, within(1e-9));
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
