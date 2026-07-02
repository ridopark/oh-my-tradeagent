package com.ohmytradeagent.orchestrator.domain;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves an STC partial-exit fraction from a free-form tail string (the trailing text after the
 * parsed BTO/STC grammar on a Discord line — e.g., "half out", "trim", "OUT") against a configured
 * keyword → fraction map.
 *
 * <p>Rules: case-insensitive substring contains; longest matching key wins (ties impossible since
 * keys are unique); null tail / empty map / no match → defaultFraction.
 */
public final class KeywordPartialMatcher {

  private KeywordPartialMatcher() {}

  /**
   * Outcome of a keyword match: the resolved {@code fraction} (the matched key's value, or the
   * caller's default when nothing matched) and the winning {@code matchedKey} (empty when the
   * default was applied because the tail was null, the map empty, or no key matched).
   *
   * <p>{@code matchedKey} is returned lower-cased — the matcher compares case-insensitively, so the
   * winning key is reported in its normalized form.
   */
  public record MatchResult(double fraction, Optional<String> matchedKey) {}

  /**
   * Existing behavior: returns the resolved fraction only. Byte-identical to the prior
   * implementation — delegates to the shared {@link #matchReporting} logic.
   */
  public static double match(String tail, Map<String, Double> fractions, double defaultFraction) {
    return matchReporting(tail, fractions, defaultFraction).fraction();
  }

  /**
   * Match-reporting variant: returns both the resolved fraction AND which key won (if any). Pure
   * and deterministic. When the tail is null, the map is null/empty, or no key matches, the
   * fraction is {@code defaultFraction} and {@code matchedKey} is empty.
   */
  public static MatchResult matchReporting(
      String tail, Map<String, Double> fractions, double defaultFraction) {
    if (tail == null || fractions == null || fractions.isEmpty()) {
      return new MatchResult(defaultFraction, Optional.empty());
    }
    String lower = tail.toLowerCase(Locale.ROOT);
    String bestKey = null;
    Double bestFraction = null;
    for (Map.Entry<String, Double> e : fractions.entrySet()) {
      String key = e.getKey();
      if (key == null || key.isEmpty()) {
        continue;
      }
      String lowerKey = key.toLowerCase(Locale.ROOT);
      if (!lower.contains(lowerKey)) {
        continue;
      }
      if (bestKey == null || lowerKey.length() > bestKey.length()) {
        bestKey = lowerKey;
        bestFraction = e.getValue();
      }
    }
    if (bestFraction == null) {
      return new MatchResult(defaultFraction, Optional.empty());
    }
    return new MatchResult(bestFraction, Optional.of(bestKey));
  }
}
