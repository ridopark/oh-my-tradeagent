package com.ohmytradeagent.orchestrator.alert;

import com.ohmytradeagent.contract.AuditEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * B2 (P0c-b1): audit-driven Discord pager for kill-switch trips. Mirrors {@link
 * OrderFailureAlerter} — same after-commit {@code @TransactionalEventListener} wiring, same red
 * embed style, same non-blocking guarantee — but owns exactly one audit kind: {@code
 * KillSwitchTripped}.
 *
 * <p>Kill-switch trips were previously NOT wired to any Discord alerter (confirmed gap). This
 * closes it: EVERY {@code KillSwitchTripped} event pages, whether it is a real {@code
 * auto:daily_loss} trip, a manual operator trip, or the new B2 {@code auto:missing_loss_threshold}
 * anomaly trip (a live strategy that reached the heartbeat with no valid loss gate). The {@code
 * reason} field on the embed distinguishes them; a single pager for all trips is the desired
 * behavior (satisfies risk Condition 4 and closes the pre-existing no-page-on-trip gap).
 *
 * <p>NON-BLOCKING GUARANTEE: {@link #onAuditEvent(AuditEvent)} never throws. The {@link
 * WebhookClient} transport is itself best-effort (a blank/unconfigured webhook URL is a no-op and
 * transport failures are swallowed-and-logged), and as belt-and-suspenders the dispatch here is
 * wrapped so any unexpected error (message-building bug, etc.) is caught and logged rather than
 * propagated into the audit write / Temporal activity. A notification must never become a
 * trading-path failure mode, and an alert failure must never roll back or block the audit commit.
 *
 * <p>Like {@link OrderFailureAlerter}, the dispatch fires from {@link
 * #onAuditCommitted(AuditEventCommitted)}, a {@code @TransactionalEventListener(phase =
 * AFTER_COMMIT, fallbackExecution = true)} handler, so a slow webhook can never hold the audit
 * {@code @Transactional} commit boundary open. {@code fallbackExecution = true} keeps the
 * no-active-transaction unit-test path firing the listener synchronously.
 */
@Component
public class KillSwitchAlerter {

  private static final Logger log = LoggerFactory.getLogger(KillSwitchAlerter.class);

  /** The single audit kind this alerter pages on. */
  private static final String KILL_SWITCH_TRIPPED_KIND = "KillSwitchTripped";

  private final WebhookClient webhookClient;
  private final TenantWebhookResolver webhookResolver;

  public KillSwitchAlerter(WebhookClient webhookClient, TenantWebhookResolver webhookResolver) {
    this.webhookClient = webhookClient;
    this.webhookResolver = webhookResolver;
  }

  /**
   * After-commit entry point. {@code AuditActivitiesImpl.log} publishes an {@link
   * AuditEventCommitted} inside its {@code @Transactional} body; this listener fires only once that
   * transaction commits (or, with {@code fallbackExecution = true}, synchronously when {@code
   * log()} ran without an active transaction — the dsl-less unit-test path). Running here means a
   * slow webhook can never delay or hold the audit transaction open.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onAuditCommitted(AuditEventCommitted committed) {
    onAuditEvent(committed.event());
  }

  /**
   * Builds and dispatches the kill-switch trip embed for a {@code KillSwitchTripped} event; returns
   * silently for every other kind. Best-effort and non-blocking: never throws. Retained as public
   * for direct unit testing of the dispatch logic.
   */
  public void onAuditEvent(AuditEvent event) {
    try {
      if (event == null
          || event.getKind() == null
          || !KILL_SWITCH_TRIPPED_KIND.equals(event.getKind())) {
        return;
      }
      String url = webhookResolver.resolve(event.getTenantId(), event.getStrategyId());
      webhookClient.postEmbedToUrl(url, buildEmbed(event));
    } catch (RuntimeException e) {
      // Defensive: a notification must never break the audit write / trading path.
      log.warn("kill-switch-alert build/dispatch failed kind={}", safeKind(event), e);
    }
  }

  /**
   * Builds the red kill-switch trip embed. The trip {@code reason} (e.g. {@code auto:daily_loss},
   * {@code auto:missing_loss_threshold}, or a manual reason) is the headline operator-actionable
   * field; {@code actor}, {@code value} (present only on a quantified daily-loss trip), {@code
   * trading_day}, and {@code tenant/strategy} round out the page. Every key is read null-safe
   * because a render that throws is swallowed by {@link #onAuditEvent}'s catch — which would
   * SILENTLY LOSE the page that exists to surface a halted real-money strategy.
   */
  private WebhookEmbed buildEmbed(AuditEvent event) {
    Map<String, Object> subject = event.getSubject();
    String reason = subjectStr(subject, "reason");

    String title = ":octagonal_sign: Kill switch TRIPPED — " + reason;

    // Phase 2 (PLAN-2026-07-15): a loss-cap trip no longer auto-flattens (subject flatten=manual /
    // auto_flatten=false). Make the page actionable — say so explicitly so the operator knows open
    // positions are still live and must be flattened by hand. Absent key (a legacy trip, or a trip
    // recorded before this policy) => no line, so the embed stays unchanged for older histories.
    String description =
        "manual".equals(subjectStr(subject, "flatten"))
            ? "Open positions were NOT auto-flattened — flatten manually if desired."
            : null;

    List<WebhookEmbed.Field> fields = new ArrayList<>();
    fields.add(new WebhookEmbed.Field("tenant_id", orNa(event.getTenantId()), false));
    fields.add(new WebhookEmbed.Field("strategy_id", orNa(event.getStrategyId()), false));
    fields.add(new WebhookEmbed.Field("reason", reason, false));
    fields.add(new WebhookEmbed.Field("actor", subjectStr(subject, "actor"), false));
    String value = subjectStr(subject, "value");
    if (!"n/a".equals(value)) {
      fields.add(new WebhookEmbed.Field("value", value, false));
    }
    fields.add(new WebhookEmbed.Field("trading_day", subjectStr(subject, "trading_day"), false));

    String footer = "workflow_id: " + orNa(event.getWorkflowId());
    return new WebhookEmbed(title, description, AlertColors.RED, footer, fields);
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
}
