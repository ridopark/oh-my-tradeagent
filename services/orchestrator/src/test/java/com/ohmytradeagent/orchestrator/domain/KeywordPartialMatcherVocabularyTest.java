package com.ohmytradeagent.orchestrator.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Data-driven regression lock for the STC partial-exit vocabulary. Encodes the CURRENTLY-DEPLOYED
 * live map and asserts {@link KeywordPartialMatcher#match} resolves each real author tail to the
 * intended fraction, so a future map edit can't silently flip a real-world outcome.
 *
 * <p>These are LINE-ONLY tails — the parser matches one line at a time, so any second-line
 * commentary an author adds is NOT part of the tail passed here.
 */
class KeywordPartialMatcherVocabularyTest {

  private static final double DEFAULT = 0.3;

  /**
   * Mirrors strategy_config.partial_fractions for copytrade-v1 as of 2026-07-01; prod_real v6 /
   * staging_paper v9. Insertion order is irrelevant to the matcher (smallest-fraction-wins as of
   * PLAN-2026-07-20) but preserved here to mirror the live config for auditability.
   */
  private static final Map<String, Double> MAP = liveMap();

  private static Map<String, Double> liveMap() {
    Map<String, Double> m = new LinkedHashMap<>();
    m.put("out", 1.0);
    m.put("all out", 1.0);
    m.put("close", 1.0);
    m.put("cutting", 1.0);
    m.put("taking the l", 1.0);
    m.put("dumping", 1.0);
    m.put("taking profit", 1.0);
    m.put("sl hit", 1.0);
    m.put("stop hit", 1.0);
    m.put("stopped out", 1.0);
    m.put("keeping stop tight", 0.9);
    m.put("two thirds", 0.67);
    m.put("keeping half", 0.5);
    m.put("half out", 0.5);
    m.put("half", 0.5);
    m.put("third", 0.33);
    m.put("partial", 0.3);
    m.put("swinging most", 0.25);
    m.put("trim", 0.25);
    m.put("holding most", 0.2);
    return m;
  }

  @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
  @MethodSource("realAuthorTails")
  void realAuthorTail_resolvesToExpectedFraction(String tail, double expected) {
    assertThat(KeywordPartialMatcher.match(tail, MAP, DEFAULT)).isEqualTo(expected, within(1e-9));
  }

  private static Stream<Arguments> realAuthorTails() {
    return Stream.of(
        // ---- Full-exit (1.0) ----
        Arguments.of("out.", 1.0),
        Arguments.of("out of the rest", 1.0),
        Arguments.of("all out", 1.0),
        Arguments.of("cutting. Bears suck", 1.0),
        Arguments.of(
            "dumping the remainder of these for now. Starting next week fresh with a clear mind",
            1.0),
        Arguments.of("stop hit not letting it go red", 1.0),
        Arguments.of("SL hit", 1.0),
        Arguments.of("stopped out", 1.0),
        Arguments.of("taking profit", 1.0),
        Arguments.of("taking the L on this one", 1.0),
        Arguments.of("close", 1.0),

        // ---- 0.9 ----
        Arguments.of("keeping stop tight", 0.9),

        // ---- 0.67 ----
        // "two thirds"(0.67) also contains the substring "third"(0.33), but substring subsumption
        // drops the subsumed "third" so the explicit "two thirds" quantity is honored — NOT a
        // collision (the shorter token is not an independent intent).
        Arguments.of("two thirds", 0.67),

        // ---- 0.5 / 0.3 ----
        // "partial. Half out keeping half" matches partial(0.3) + half*(0.5) + out(1.0):
        // smallest-fraction-wins resolves to 0.3 (was 0.5 under longest-key-wins).
        Arguments.of("partial. Half out keeping half", 0.3),
        Arguments.of("half out", 0.5),

        // ---- 0.33 ----
        Arguments.of("sold a third", 0.33),

        // ---- 0.3 (partial keyword — NOT default) ----
        Arguments.of("PARTIAL", 0.3),
        Arguments.of("partial 💸", 0.3),
        Arguments.of("partial taking a few profits. Can't get greedy", 0.3),
        Arguments.of(
            "partial taking more here since the market is all over the place this morning. "
                + "Still holding 1/4 position",
            0.3),
        Arguments.of("partial still mainly in", 0.3),
        Arguments.of("partial. Make it free.", 0.3),
        Arguments.of("much better PARTIAL. Holding 1/4", 0.3),
        Arguments.of(
            "partial. Not sure how much we're gonna be able to get here with it being choppy early.",
            0.3),
        Arguments.of("make her free PARTIAL", 0.3),

        // ---- 0.25 ----
        Arguments.of("trimming into strength", 0.25),
        Arguments.of("swinging most", 0.25),

        // ---- 0.2 ----
        Arguments.of("partial lets take some here at support. Holding most", 0.2),

        // ---- Default (0.3): unmatched, NO key substring present ----
        Arguments.of("", 0.3),
        Arguments.of("   ", 0.3),
        Arguments.of("yolo it", 0.3),
        Arguments.of("gg wp", 0.3),

        // ---- Documented substring footgun: contains "out" -> 1.0 (NOT default) ----
        Arguments.of("scaling out of a bit", 1.0));
  }

  // ---- Explicit KNOWN-BEHAVIOR locks (PLAN-2026-07-20: conservative on collision) ----

  @Test
  void takingProfitPlusHoldingMost_resolvesToHoldingMost() {
    // The exact 2026-07-20 defect class: "taking profit"(1.0) + "holding most"(0.2) collide;
    // longest-key-wins full-closed (1.0) a position the author is holding. Smallest-fraction-wins
    // now resolves to 0.2 (keep most), matching author intent, and flags the collision.
    assertThat(KeywordPartialMatcher.match("taking profit here, holding most of it", MAP, DEFAULT))
        .isEqualTo(0.2, within(1e-9));
  }

  @Test
  void cuttingPlusHalf_resolvesToHalf() {
    // "cutting"(1.0) + "half"(0.5) collide; longest-key-wins full-closed (1.0) even though the
    // author said half. Smallest-fraction-wins now resolves to 0.5, matching intent.
    assertThat(KeywordPartialMatcher.match("cutting half here", MAP, DEFAULT))
        .isEqualTo(0.5, within(1e-9));
  }

  @Test
  void emptyTail_fallsToDefault() {
    assertThat(KeywordPartialMatcher.match("", MAP, DEFAULT)).isEqualTo(0.3, within(1e-9));
  }
}
