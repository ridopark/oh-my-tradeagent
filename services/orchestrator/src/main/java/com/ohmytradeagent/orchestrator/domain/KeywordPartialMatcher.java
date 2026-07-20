package com.ohmytradeagent.orchestrator.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves an STC partial-exit fraction from a free-form tail string (the trailing text after the
 * parsed BTO/STC grammar on a Discord line — e.g., "half out", "trim", "OUT") against a configured
 * keyword → fraction map.
 *
 * <p>Rules: case-insensitive substring contains. A matched key that is a substring of another
 * matched key is dropped (the longer, more-specific phrase subsumes it — "two thirds" subsumes
 * "third", "half out" subsumes "out"). Among the surviving MAXIMAL keys, when they map to DIFFERENT
 * fractions the SMALLEST (most-conservative) fraction wins so an explicitly-partial signal is never
 * over-liquidated (ties in fraction keep the longest key for a stable {@code matchedKey}); null
 * tail / empty map / no match → defaultFraction. A multi-fraction match among the maximal keys is
 * reported via {@link MatchResult#fractionCollision()} so an out-of-workflow alerter can page the
 * operator to verify the conservative auto-pick.
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
   *
   * <p>{@code fractionCollision} is {@code true} when ≥2 MAXIMAL matched keys (after substring
   * subsumption) map to DIFFERENT fraction values (the smallest was applied); {@code false} for a
   * single match, a same-fraction multi-match, or the default/no-match case.
   *
   * <p>{@code matchedKeys} is the set of MAXIMAL matched keys (lower-cased, in map iteration order;
   * substring-subsumed keys removed) — empty when nothing matched. Carried into the collision audit
   * subject so the operator can see the phrases that drove the decision.
   */
  public record MatchResult(
      double fraction,
      Optional<String> matchedKey,
      boolean fractionCollision,
      List<String> matchedKeys) {}

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
      return new MatchResult(defaultFraction, Optional.empty(), false, List.of());
    }
    String lower = tail.toLowerCase(Locale.ROOT);

    // Every keyword whose lower-cased form occurs in the tail, deduped by key, iteration order.
    Map<String, Double> matched = new LinkedHashMap<>();
    for (Map.Entry<String, Double> e : fractions.entrySet()) {
      String key = e.getKey();
      if (key == null || key.isEmpty()) {
        continue;
      }
      String lowerKey = key.toLowerCase(Locale.ROOT);
      if (lower.contains(lowerKey)) {
        matched.putIfAbsent(lowerKey, e.getValue());
      }
    }
    if (matched.isEmpty()) {
      return new MatchResult(defaultFraction, Optional.empty(), false, List.of());
    }

    // Keep only MAXIMAL matched keys: drop any key that is a substring of another DISTINCT matched
    // key. A longer, more-specific phrase subsumes the shorter token it contains ("two thirds"
    // subsumes "third"; "half out" and "stopped out" subsume "out"), so the shorter token is not an
    // independent intent. Without this, a substring accident both picks the wrong fraction (e.g.
    // "two thirds" → 0.33 via "third") and raises a spurious collision alert (every "half out"
    // matching "out"). Among the survivors the SMALLEST fraction wins (conservative — an
    // explicitly-partial signal is never over-liquidated); on a tie keep the longest key for a
    // stable matchedKey.
    String bestKey = null;
    Double bestFraction = null;
    Set<Double> maximalFractions = new LinkedHashSet<>();
    List<String> maximalKeys = new ArrayList<>();
    for (Map.Entry<String, Double> e : matched.entrySet()) {
      String lowerKey = e.getKey();
      boolean subsumed = false;
      for (String other : matched.keySet()) {
        if (!other.equals(lowerKey) && other.contains(lowerKey)) {
          subsumed = true;
          break;
        }
      }
      if (subsumed) {
        continue;
      }
      double value = e.getValue();
      maximalFractions.add(value);
      maximalKeys.add(lowerKey);
      if (bestFraction == null
          || value < bestFraction
          || (value == bestFraction && lowerKey.length() > bestKey.length())) {
        bestKey = lowerKey;
        bestFraction = value;
      }
    }
    return new MatchResult(
        bestFraction, Optional.of(bestKey), maximalFractions.size() >= 2, maximalKeys);
  }
}
