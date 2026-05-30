package com.ohmytradeagent.orchestrator.alert;

import com.ohmytradeagent.contract.AuditEvent;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Issue #297: audit-driven Discord alerter for copytrade BTO/STC order failures.
 *
 * <p>{@code AuditActivitiesImpl.log(AuditEvent)} is the single funnel every failure-classified
 * {@code audit_log} row passes through. After the audit row is written, the activity calls {@link
 * #onAuditEvent(AuditEvent)}; this class checks the event's {@code kind} against a configurable
 * allowlist ({@code alert.discord.failure-kinds}, default {@code OrphanSTC,EntryExpired}) and, on a
 * match, builds a human-readable message from the event (action, symbol, reason, and enough
 * identifiers — {@code signal_id}, {@code option_symbol}, {@code workflow_id} — to find the
 * failure) and dispatches it via the {@link WebhookClient}.
 *
 * <p>Issue #308 de-dupe: {@code SignalRejected} was REMOVED from the default allowlist. The full
 * signal feed (received + accepted/rejected/avg-skipped outcomes) is now mirrored by {@link
 * SignalFeedAlerter}, which owns {@code SignalRejected} as the outcome:rejected message. Keeping it
 * here too would post a rejected signal TWICE. {@code OrphanSTC} / {@code EntryExpired} / broker
 * {@code placeOrder} rejections remain distinct failure alerts owned by this class.
 *
 * <p>Issue #311: the feed-mirror toggle ({@code alert.discord.signal-feed.enabled} / {@code
 * ALERT_SIGNAL_FEED_ENABLED}) defaults to OFF in code, which would create a no-alert gap for {@code
 * SignalRejected} when an operator never sets it. To close that gap, this alerter now AUTOMATICALLY
 * UNIONS {@code SignalRejected} into its effective allowlist whenever the feed toggle is OFF
 * (computed once at construction). When the feed toggle is ON, {@link SignalFeedAlerter}'s {@code
 * outcome:rejected} path owns the rejection alert exclusively, so {@code SignalRejected} stays
 * absent from this alerter's effective allowlist (de-dupe preserved).
 *
 * <p><b>Alerter ownership matrix</b> (rows = audit kinds, columns = feed toggle state):
 *
 * <pre>
 *   kind            | feed-off                            | feed-on
 *   ----------------|-------------------------------------|--------------------------------------
 *   SignalReceived  | (ignored)                           | SignalFeedAlerter (received message)
 *   SignalAccepted  | (ignored)                           | SignalFeedAlerter (outcome:accepted)
 *   SignalRejected  | OrderFailureAlerter (auto-unioned)  | SignalFeedAlerter (outcome:rejected)
 *   AvgSkipped      | (ignored)                           | SignalFeedAlerter (AVG skipped)
 *   OrphanSTC       | OrderFailureAlerter (STC failure)   | OrderFailureAlerter (STC failure)
 *   EntryExpired    | OrderFailureAlerter (BTO failure)   | OrderFailureAlerter (BTO failure)
 * </pre>
 *
 * <p>The "auto-unioned" cell is the issue #311 regression guard: a rejected signal still posts
 * exactly ONE Discord message regardless of the toggle state — and never zero.
 *
 * <p>Non-allowlisted kinds (e.g. {@code SignalReceived} when the feed is off) are ignored, avoiding
 * channel spam.
 *
 * <p>NON-BLOCKING GUARANTEE: {@link #onAuditEvent} never throws. The webhook client is itself
 * best-effort, but as a belt-and-suspenders the dispatch is wrapped so that any unexpected error
 * (message-building bug, etc.) is caught and logged rather than propagated into the audit write /
 * Temporal activity. A notification feature must not become a trading-path failure mode.
 *
 * <p>Issue #302: the dispatch is invoked from {@link #onAuditCommitted(AuditEventCommitted)}, a
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)} handler, so a
 * slow ~5s webhook can never hold the audit {@code @Transactional} commit boundary open. {@code
 * fallbackExecution = true} keeps the no-active-transaction unit-test path firing the listener
 * synchronously.
 */
@Component
public class OrderFailureAlerter {

  private static final Logger log = LoggerFactory.getLogger(OrderFailureAlerter.class);

  // Issue #308: SignalRejected dropped from the default — it is now owned by SignalFeedAlerter's
  // outcome:rejected mirror so a rejected signal posts exactly one Discord message.
  // Issue #311: when the feed toggle is OFF, we union SignalRejected back in at construction so
  // the no-alert gap can't recur if an operator forgets to flip the feed on.
  private static final String DEFAULT_FAILURE_KINDS = "OrphanSTC,EntryExpired";

  private static final String SIGNAL_REJECTED_KIND = "SignalRejected";

  /** STC (exit) failure kinds; everything else in the allowlist is treated as a BTO (entry). */
  private static final Set<String> STC_KINDS = Set.of("OrphanSTC");

  private final WebhookClient webhookClient;
  private final Set<String> failureKinds;

  public OrderFailureAlerter(
      WebhookClient webhookClient,
      @Value("${alert.discord.failure-kinds:" + DEFAULT_FAILURE_KINDS + "}") String failureKinds,
      @Value("${alert.discord.signal-feed.enabled:false}") boolean signalFeedEnabled) {
    this.webhookClient = webhookClient;
    Set<String> parsed =
        Arrays.stream(failureKinds.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toCollection(HashSet::new));
    // Issue #311: when the feed mirror is OFF, ensure SignalRejected has an owner here so the
    // no-alert gap (#311) cannot recur. When the feed is ON, SignalFeedAlerter owns it and we
    // must NOT add it here (otherwise a rejected signal would post twice — the #308 invariant).
    if (!signalFeedEnabled) {
      parsed.add(SIGNAL_REJECTED_KIND);
    }
    this.failureKinds = Set.copyOf(parsed);
  }

  /**
   * Issue #302: after-commit entry point. {@code AuditActivitiesImpl.log} publishes an {@link
   * AuditEventCommitted} inside its {@code @Transactional} body; this listener fires only once that
   * transaction has committed (or, with {@code fallbackExecution = true}, synchronously when {@code
   * log()} ran without an active transaction — the dsl-less unit-test path). Running here means a
   * slow webhook can never delay or hold the audit transaction open.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onAuditCommitted(AuditEventCommitted committed) {
    onAuditEvent(committed.event());
  }

  /**
   * Called (via {@link #onAuditCommitted}) AFTER the audit row is persisted and the transaction
   * commits. Best-effort and non-blocking: returns silently for non-allowlisted kinds and never
   * throws. Retained as package/public for direct unit testing of the dispatch logic.
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
