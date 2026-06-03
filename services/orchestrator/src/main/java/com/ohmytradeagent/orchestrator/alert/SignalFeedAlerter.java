package com.ohmytradeagent.orchestrator.alert;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.identity.YahooOptionLink;
import java.util.ArrayList;
import java.util.List;
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

  // Severity-appropriate Discord accent colors (decimal RGB) per the plan's color matrix.
  private static final int DISCORD_BLURPLE = 5793266; // info — signal received
  private static final int DISCORD_GREEN = 5763719; // success — signal accepted
  private static final int DISCORD_RED = 15548997; // failure — signal rejected
  private static final int DISCORD_YELLOW = 16705372; // warn — AVG skipped

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
      WebhookEmbed embed = buildEmbed(event);
      if (embed == null) {
        return;
      }
      webhookClient.postEmbed(embed);
    } catch (RuntimeException e) {
      // Defensive: a notification must never break the audit write / trading path.
      log.warn("signal-feed-alert build/dispatch failed kind={}", safeKind(event), e);
    }
  }

  /** Returns the embed for a signal-feed kind, or {@code null} for any other kind. */
  private WebhookEmbed buildEmbed(AuditEvent event) {
    return switch (event.getKind()) {
      case KIND_SIGNAL_RECEIVED -> buildReceived(event);
      case KIND_SIGNAL_ACCEPTED -> buildAccepted(event);
      case KIND_SIGNAL_REJECTED -> buildRejected(event);
      case KIND_AVG_SKIPPED -> buildAvgSkipped(event);
      default -> null;
    };
  }

  /**
   * Signal received (blurple/info): the contract field is constructed from
   * ticker+expiry+strike+right (this path may not have a resolved {@code option_symbol}) into a
   * Yahoo link, falling back to readable plain text when any part is missing.
   */
  private WebhookEmbed buildReceived(AuditEvent event) {
    Map<String, Object> s = event.getSubject();
    String title =
        ":satellite: Signal received — " + subjectStr(s, "action") + " " + subjectStr(s, "ticker");
    List<WebhookEmbed.Field> fields = new ArrayList<>();
    fields.add(new WebhookEmbed.Field("contract", contractLinkFromParts(s), false));
    fields.add(new WebhookEmbed.Field("price", subjectStr(s, "price"), false));
    fields.add(new WebhookEmbed.Field("author", subjectStr(s, "author"), false));
    fields.add(new WebhookEmbed.Field("posted_at", subjectStr(s, "posted_at"), false));
    addCommonFields(fields, event);
    return new WebhookEmbed(title, null, DISCORD_BLURPLE, footer(event), fields);
  }

  /** Signal accepted (green/success): the resolved {@code option_symbol} becomes a Yahoo link. */
  private WebhookEmbed buildAccepted(AuditEvent event) {
    Map<String, Object> s = event.getSubject();
    String title = ":white_check_mark: Signal accepted — BTO (entry)";
    List<WebhookEmbed.Field> fields = new ArrayList<>();
    fields.add(
        new WebhookEmbed.Field(
            "symbol", YahooOptionLink.markdown(rawSubject(s, "option_symbol")), false));
    fields.add(
        new WebhookEmbed.Field(
            "accepted",
            "×" + subjectStr(s, "contracts") + " @ ref_premium " + subjectStr(s, "ref_premium"),
            false));
    addCommonFields(fields, event);
    return new WebhookEmbed(title, null, DISCORD_GREEN, footer(event), fields);
  }

  /**
   * Signal rejected (red/failure): prefers a resolved {@code option_symbol}, else constructs the
   * contract from ticker+expiry+strike+right (per the plan's color matrix), else plain text.
   */
  private WebhookEmbed buildRejected(AuditEvent event) {
    Map<String, Object> s = event.getSubject();
    String title = ":no_entry: Signal rejected — BTO (entry)";
    List<WebhookEmbed.Field> fields = new ArrayList<>();
    fields.add(new WebhookEmbed.Field("contract", rejectedContractLink(s), false));
    fields.add(new WebhookEmbed.Field("rejected", reasonOf(s), false));
    addCommonFields(fields, event);
    return new WebhookEmbed(title, null, DISCORD_RED, footer(event), fields);
  }

  /** AVG skipped (yellow/warn): no contract in the subject, so no Yahoo link. */
  private WebhookEmbed buildAvgSkipped(AuditEvent event) {
    Map<String, Object> s = event.getSubject();
    String title = ":fast_forward: AVG skipped";
    List<WebhookEmbed.Field> fields = new ArrayList<>();
    fields.add(new WebhookEmbed.Field("note", subjectStr(s, "note"), false));
    addCommonFields(fields, event);
    return new WebhookEmbed(title, null, DISCORD_YELLOW, footer(event), fields);
  }

  /** Builds the Yahoo link from ticker+expiry+strike+right parts (signal-received path). */
  private static String contractLinkFromParts(Map<String, Object> s) {
    return YahooOptionLink.markdownFromParts(
        rawSubject(s, "ticker"),
        rawSubject(s, "expiry"),
        rightChar(rawSubject(s, "right")),
        rawSubject(s, "strike"));
  }

  /**
   * Rejected-path contract: prefer a resolved {@code option_symbol} when present, else construct
   * from ticker+expiry+strike+right, else plain text.
   */
  private static String rejectedContractLink(Map<String, Object> s) {
    String resolved = rawSubject(s, "option_symbol");
    if (resolved != null && !resolved.isBlank()) {
      return YahooOptionLink.markdown(resolved);
    }
    return contractLinkFromParts(s);
  }

  /** The signal_id field common to every feed message so a signal can be traced end-to-end. */
  private static void addCommonFields(List<WebhookEmbed.Field> fields, AuditEvent event) {
    fields.add(
        new WebhookEmbed.Field("signal_id", subjectStr(event.getSubject(), "signal_id"), false));
  }

  /** Low-signal trace (workflow_id + tenant/strategy) demoted to the footer. */
  private static String footer(AuditEvent event) {
    return "workflow_id: "
        + orNa(event.getWorkflowId())
        + " | tenant/strategy: "
        + orNa(event.getTenantId())
        + "/"
        + orNa(event.getStrategyId());
  }

  private static char rightChar(String right) {
    if (right == null || right.isBlank()) {
      return ' ';
    }
    return right.trim().charAt(0);
  }

  /** Raw subject value (may be {@code null}) — for the Yahoo helper, which handles null itself. */
  private static String rawSubject(Map<String, Object> subject, String key) {
    if (subject == null) {
      return null;
    }
    Object value = subject.get(key);
    return value == null ? null : String.valueOf(value);
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
