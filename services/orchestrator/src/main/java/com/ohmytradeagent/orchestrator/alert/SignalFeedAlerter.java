package com.ohmytradeagent.orchestrator.alert;

import com.ohmytradeagent.contract.AuditEvent;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Issue #308: audit-driven Discord mirror of the FULL copytrade signal feed.
 *
 * <p>Where {@link OrderFailureAlerter} (issues #297/#302) posts only the small failure-kind
 * allowlist, this alerter mirrors every BTO/STC/AVG signal as two messages to the SAME {@code
 * discord-alert-credentials} webhook ({@code ALERT_DISCORD_WEBHOOK_URL}):
 *
 * <ul>
 *   <li>a <b>received</b> message at the {@code SignalReceived} ingest point (action / ticker /
 *       expiry / strike / right / price / author), so the feed renders ~immediately; and
 *   <li>the matching <b>outcome</b> message at {@code SignalAccepted} ({@code accepted ×N @
 *       ref_premium}), {@code SignalRejected} ({@code rejected: <reason>}), or {@code AvgSkipped}
 *       ({@code AVG skipped: <note>}).
 * </ul>
 *
 * <p>It reuses the exact same dispatch seam as #297/#302: {@code AuditActivitiesImpl.log} publishes
 * an {@link AuditEventCommitted} inside its {@code @Transactional} body and this
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)} handler runs
 * the (potentially slow ~5s) webhook only AFTER the audit transaction commits — never holding the
 * audit DB transaction open. No new dispatch infrastructure is introduced.
 *
 * <p>CRITICAL de-dupe (issue #308): {@code SignalRejected} is mirrored ONLY here, as the
 * outcome:rejected message. It was removed from {@link OrderFailureAlerter}'s default failure-kind
 * allowlist ({@code application.yml}) so a rejected signal posts EXACTLY ONE Discord message rather
 * than a #297 failure alert AND this mirror. {@code OrphanSTC} / {@code EntryExpired} / broker
 * {@code placeOrder} rejections remain distinct #297 failure alerts.
 *
 * <p>TOGGLE: gated by {@code alert.discord.signal-feed.enabled} ({@code ALERT_SIGNAL_FEED_ENABLED},
 * default {@code false}) so the high-volume feed mirror can be turned on/off independently of the
 * failure alerts (both read the same webhook URL). When disabled this alerter is a complete no-op.
 *
 * <p>NON-BLOCKING GUARANTEE: {@link #onAuditEvent} never throws. Any error (message-building bug,
 * webhook failure surfaced through a misbehaving client) is caught and logged at WARN rather than
 * propagated into the audit write / Temporal activity. A notification feature must not become a
 * trading-path failure mode (the #295 lesson).
 */
@Component
public class SignalFeedAlerter {

  private static final Logger log = LoggerFactory.getLogger(SignalFeedAlerter.class);

  private static final String KIND_SIGNAL_RECEIVED = "SignalReceived";
  private static final String KIND_SIGNAL_ACCEPTED = "SignalAccepted";
  private static final String KIND_SIGNAL_REJECTED = "SignalRejected";
  private static final String KIND_AVG_SKIPPED = "AvgSkipped";

  private final WebhookClient webhookClient;
  private final boolean enabled;

  public SignalFeedAlerter(
      WebhookClient webhookClient,
      @Value("${alert.discord.signal-feed.enabled:false}") boolean enabled) {
    this.webhookClient = webhookClient;
    this.enabled = enabled;
  }

  /**
   * Issue #308: after-commit entry point, mirroring the #302 seam. Fires once the audit transaction
   * commits (or synchronously via {@code fallbackExecution = true} on the no-active-transaction
   * unit-test path). Running here means a slow webhook can never delay or hold the audit
   * transaction open.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onAuditCommitted(AuditEventCommitted committed) {
    onAuditEvent(committed.event());
  }

  /**
   * Called (via {@link #onAuditCommitted}) AFTER the audit row is persisted and the transaction
   * commits. Best-effort and non-blocking: no-ops when the feed toggle is off or the kind is not a
   * signal-feed kind, and never throws. Retained as public for direct unit testing of the dispatch
   * logic.
   */
  public void onAuditEvent(AuditEvent event) {
    if (!enabled) {
      return;
    }
    try {
      if (event == null || event.getKind() == null) {
        return;
      }
      String message = buildMessage(event);
      if (message == null) {
        return;
      }
      webhookClient.post(message);
    } catch (RuntimeException e) {
      // Defensive: a notification must never break the audit write / trading path.
      log.warn("signal-feed-alert build/dispatch failed kind={}", safeKind(event), e);
    }
  }

  /** Returns the formatted message for a signal-feed kind, or {@code null} for any other kind. */
  private String buildMessage(AuditEvent event) {
    return switch (event.getKind()) {
      case KIND_SIGNAL_RECEIVED -> buildReceived(event);
      case KIND_SIGNAL_ACCEPTED -> buildAccepted(event);
      case KIND_SIGNAL_REJECTED -> buildRejected(event);
      case KIND_AVG_SKIPPED -> buildAvgSkipped(event);
      default -> null;
    };
  }

  private String buildReceived(AuditEvent event) {
    Map<String, Object> s = event.getSubject();
    StringBuilder sb = new StringBuilder();
    sb.append(":satellite: Signal received — ")
        .append(subjectStr(s, "action"))
        .append(' ')
        .append(subjectStr(s, "ticker"))
        .append('\n')
        .append("contract: ")
        .append(subjectStr(s, "expiry"))
        .append(' ')
        .append(subjectStr(s, "strike"))
        .append(subjectStr(s, "right"))
        .append('\n')
        .append("price: ")
        .append(subjectStr(s, "price"))
        .append('\n')
        .append("author: ")
        .append(subjectStr(s, "author"))
        .append('\n')
        .append("posted_at: ")
        .append(subjectStr(s, "posted_at"))
        .append('\n');
    appendCommonTail(sb, event);
    return sb.toString();
  }

  private String buildAccepted(AuditEvent event) {
    Map<String, Object> s = event.getSubject();
    StringBuilder sb = new StringBuilder();
    sb.append(":white_check_mark: Signal accepted — BTO (entry)")
        .append('\n')
        .append("symbol: ")
        .append(subjectStr(s, "option_symbol"))
        .append('\n')
        .append("accepted ×")
        .append(subjectStr(s, "contracts"))
        .append(" @ ref_premium ")
        .append(subjectStr(s, "ref_premium"))
        .append('\n');
    appendCommonTail(sb, event);
    return sb.toString();
  }

  private String buildRejected(AuditEvent event) {
    Map<String, Object> s = event.getSubject();
    StringBuilder sb = new StringBuilder();
    sb.append(":no_entry: Signal rejected — BTO (entry)")
        .append('\n')
        .append("rejected: ")
        .append(reasonOf(s))
        .append('\n');
    appendCommonTail(sb, event);
    return sb.toString();
  }

  private String buildAvgSkipped(AuditEvent event) {
    Map<String, Object> s = event.getSubject();
    StringBuilder sb = new StringBuilder();
    sb.append(":fast_forward: AVG skipped")
        .append('\n')
        .append("note: ")
        .append(subjectStr(s, "note"))
        .append('\n');
    appendCommonTail(sb, event);
    return sb.toString();
  }

  /** Appends the identifiers common to every feed message so a signal can be traced end-to-end. */
  private static void appendCommonTail(StringBuilder sb, AuditEvent event) {
    sb.append("signal_id: ")
        .append(subjectStr(event.getSubject(), "signal_id"))
        .append('\n')
        .append("workflow_id: ")
        .append(orNa(event.getWorkflowId()))
        .append('\n')
        .append("tenant/strategy: ")
        .append(orNa(event.getTenantId()))
        .append('/')
        .append(orNa(event.getStrategyId()));
  }

  private static String reasonOf(Map<String, Object> subject) {
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
    return "n/a";
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

  /** Visible for testing: whether the feed mirror is enabled. */
  boolean enabled() {
    return enabled;
  }
}
