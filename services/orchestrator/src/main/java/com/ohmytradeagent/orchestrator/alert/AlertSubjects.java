package com.ohmytradeagent.orchestrator.alert;

import java.util.Map;

/**
 * Small subject-accessor helper shared by the orchestrator trade alerters. Centralised here so
 * {@link OrderFailureAlerter} and {@link SignalFeedAlerter} reference one source rather than each
 * re-declaring an identical private method. In-service only — no cross-service boundary is crossed
 * (the exec alerters keep their own accessors).
 */
final class AlertSubjects {

  private AlertSubjects() {}

  /** Raw subject value (may be {@code null}) — for the Yahoo helper, which handles null itself. */
  static String rawSubject(Map<String, Object> subject, String key) {
    if (subject == null) {
      return null;
    }
    Object value = subject.get(key);
    return value == null ? null : String.valueOf(value);
  }

  /** Trimmed subject value, or {@code null} when absent/blank — for presence-gated fields. */
  static String trimmedSubject(Map<String, Object> subject, String key) {
    String raw = rawSubject(subject, key);
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /** Integer subject value, or {@code null} when absent/blank/non-numeric. */
  static Integer subjectInt(Map<String, Object> subject, String key) {
    String s = trimmedSubject(subject, key);
    if (s == null) {
      return null;
    }
    try {
      return Integer.valueOf(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Renders an {@code open_mtm} subject value (unrealized P&L, computed {@code
   * (bid−entry)×qty×100}) as a signed whole-dollar amount — {@code +$1,551} for a gain, {@code
   * -$2,500} for a loss — so an unsigned value can never be misread as underwater.
   * Null/blank/non-numeric => {@code "n/a"}. Shared by {@link KillSwitchAlerter} and {@link
   * AccountKillSwitchCapAlerter}.
   */
  static String signedUnrealizedPnl(String raw) {
    if (raw == null) {
      return "n/a";
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty() || "n/a".equals(trimmed)) {
      return "n/a";
    }
    double parsed;
    try {
      parsed = Double.parseDouble(trimmed);
    } catch (NumberFormatException e) {
      return "n/a";
    }
    long dollars = Math.round(parsed);
    String sign = dollars >= 0 ? "+" : "-";
    return sign + "$" + String.format(java.util.Locale.US, "%,d", Math.abs(dollars));
  }
}
