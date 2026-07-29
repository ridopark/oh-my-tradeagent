package com.ohmytradeagent.orchestrator.domain;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Detects a scale-in cue in the free-form tail of a copytrade BTO line (the trailing text after the
 * parsed BTO grammar — e.g. "risky, scaling in"). When the author signals they are entering small
 * and will add later ("scaling in" / "scale in" / "starter" / "small size" / "half size"), the
 * copytrade sizing path reduces the initial entry by a configured fraction (see {@link
 * Sizing#computeContracts(com.ohmytradeagent.contract.CopytradeSignalPayload,
 * com.ohmytradeagent.contract.StrategyConfig, java.math.BigDecimal, java.math.BigDecimal)}).
 *
 * <p>Pure and deterministic — no I/O, no clock, no randomness — so it is safe to call from workflow
 * code. Mirrors the style of {@link Sizing} and {@link KeywordPartialMatcher}. Case-insensitive
 * substring contains; the FIRST cue phrase (in vocabulary order) present in the tail is returned,
 * lower-cased. Null / blank tail → empty.
 */
public final class ScaleInMatcher {

  /**
   * The scale-in cue vocabulary. EXACTLY these five phrases — "risky" is intentionally NOT a cue
   * (an author can be "risky" while entering full size). Order is the match precedence.
   */
  private static final List<String> PHRASES =
      List.of("scaling in", "scale in", "starter", "small size", "half size");

  private ScaleInMatcher() {}

  /**
   * @param tail free-form trailing text of a BTO line
   * @return the first matched cue phrase (lower-cased) contained in {@code tail}, or empty when the
   *     tail is null/blank or contains no cue.
   */
  public static Optional<String> match(String tail) {
    if (tail == null || tail.isBlank()) {
      return Optional.empty();
    }
    String lower = tail.toLowerCase(Locale.ROOT);
    for (String phrase : PHRASES) {
      if (lower.contains(phrase)) {
        return Optional.of(phrase);
      }
    }
    return Optional.empty();
  }
}
