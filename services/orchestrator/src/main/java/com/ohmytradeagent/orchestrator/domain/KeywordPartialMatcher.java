package com.ohmytradeagent.orchestrator.domain;

import java.util.Locale;
import java.util.Map;

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

  public static double match(String tail, Map<String, Double> fractions, double defaultFraction) {
    if (tail == null || fractions == null || fractions.isEmpty()) {
      return defaultFraction;
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
    return bestFraction != null ? bestFraction : defaultFraction;
  }
}
