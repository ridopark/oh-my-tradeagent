package com.ohmytradeagent.orchestrator.alert;

import com.ohmytradeagent.contract.AuditEvent;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Issue #297: audit-driven Discord alerter for copytrade BTO/STC order failures.
 *
 * <p>{@code AuditActivitiesImpl.log(AuditEvent)} is the single funnel every failure-classified
 * {@code audit_log} row passes through. After the audit row is written, the activity calls {@link
 * #onAuditEvent(AuditEvent)}; this class checks the event's {@code kind} against a configurable
 * allowlist ({@code alert.discord.failure-kinds}, default {@code SignalRejected,OrphanSTC,
 * EntryExpired}) and, on a match, builds a human-readable message from the event (action, symbol,
 * reason, and enough identifiers — {@code signal_id}, {@code option_symbol}, {@code workflow_id} —
 * to find the failure) and dispatches it via the {@link WebhookClient}.
 *
 * <p>Non-allowlisted kinds (e.g. {@code SignalReceived}) are ignored, avoiding channel spam.
 *
 * <p>NON-BLOCKING GUARANTEE: {@link #onAuditEvent} never throws. The webhook client is itself
 * best-effort, but as a belt-and-suspenders the dispatch is wrapped so that any unexpected error
 * (message-building bug, etc.) is caught and logged rather than propagated into the audit write /
 * Temporal activity. A notification feature must not become a trading-path failure mode.
 */
@Component
public class OrderFailureAlerter {

  private static final Logger log = LoggerFactory.getLogger(OrderFailureAlerter.class);

  private static final String DEFAULT_FAILURE_KINDS = "SignalRejected,OrphanSTC,EntryExpired";

  /** STC (exit) failure kinds; everything else in the allowlist is treated as a BTO (entry). */
  private static final Set<String> STC_KINDS = Set.of("OrphanSTC");

  private final WebhookClient webhookClient;
  private final Set<String> failureKinds;

  public OrderFailureAlerter(
      WebhookClient webhookClient,
      @Value("${alert.discord.failure-kinds:" + DEFAULT_FAILURE_KINDS + "}") String failureKinds) {
    this.webhookClient = webhookClient;
    this.failureKinds =
        Arrays.stream(failureKinds.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Called by {@code AuditActivitiesImpl.log} AFTER the audit row is persisted. Best-effort and
   * non-blocking: returns silently for non-allowlisted kinds and never throws.
   */
  public void onAuditEvent(AuditEvent event) {
    try {
      if (event == null || event.getKind() == null || !failureKinds.contains(event.getKind())) {
        return;
      }
      webhookClient.post(buildMessage(event));
    } catch (RuntimeException e) {
      // Defensive: a notification must never break the audit write / trading path.
      log.warn("order-failure-alert build/dispatch failed kind={}", safeKind(event), e);
    }
  }

  private String buildMessage(AuditEvent event) {
    Map<String, Object> subject = event.getSubject();
    String action = STC_KINDS.contains(event.getKind()) ? "STC (exit)" : "BTO (entry)";
    String symbol = subjectStr(subject, "option_symbol");
    String reason = reasonOf(event.getKind(), subject);
    String signalId = subjectStr(subject, "signal_id");

    StringBuilder sb = new StringBuilder();
    sb.append(":rotating_light: Copytrade order FAILED — ")
        .append(action)
        .append('\n')
        .append("kind: ")
        .append(event.getKind())
        .append('\n')
        .append("symbol: ")
        .append(symbol)
        .append('\n')
        .append("reason: ")
        .append(reason)
        .append('\n')
        .append("signal_id: ")
        .append(signalId)
        .append('\n')
        .append("workflow_id: ")
        .append(orNa(event.getWorkflowId()))
        .append('\n')
        .append("tenant/strategy: ")
        .append(orNa(event.getTenantId()))
        .append('/')
        .append(orNa(event.getStrategyId()));
    return sb.toString();
  }

  private static String reasonOf(String kind, Map<String, Object> subject) {
    String code = subjectStr(subject, "reason_code");
    String detail = subjectStr(subject, "reason_detail");
    if (!"n/a".equals(code) && !"n/a".equals(detail)) {
      return code + " — " + detail;
    }
    if (!"n/a".equals(code)) {
      return code;
    }
    if (!"n/a".equals(detail)) {
      return detail;
    }
    // EntryExpired and similar kinds carry no reason_code; the kind IS the reason.
    return kind;
  }

  private static String subjectStr(Map<String, Object> subject, String key) {
    if (subject == null) {
      return "n/a";
    }
    Object value = subject.get(key);
    return value == null ? "n/a" : String.valueOf(value);
  }

  private static String orNa(String value) {
    return value == null || value.isBlank() ? "n/a" : value;
  }

  private static String safeKind(AuditEvent event) {
    return event == null ? "null" : String.valueOf(event.getKind());
  }

  /** Visible for testing: the resolved, normalized allowlist. */
  Set<String> failureKinds() {
    return failureKinds;
  }
}
