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
}
