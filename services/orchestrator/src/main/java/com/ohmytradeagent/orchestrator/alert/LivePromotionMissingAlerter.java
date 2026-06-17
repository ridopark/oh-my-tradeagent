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
 * P3-a (multi-tenant-broker-credentials): audit-driven Discord pager for the live-promotion gate
 * refusal. Mirrors {@link KillSwitchAlerter} — same after-commit
 * {@code @TransactionalEventListener} wiring, same red embed style, same non-blocking guarantee —
 * but owns exactly one audit kind: {@code LivePromotionMissing}.
 *
 * <p>Every {@code LivePromotionMissing} event pages. It means a LIVE (real-money) BTO was REFUSED
 * at dispatch because no fresh {@code LivePromotionApproved} row exists for its broker_target (or
 * the verify failed closed). The strategy is live but unpromoted — an operator must approve the
 * promotion (dual-control) before real-money entries can flow. The {@code reason} field ({@code
 * absent} / {@code stale} / {@code verify_error}) distinguishes the cause.
 *
 * <p>NON-BLOCKING GUARANTEE: {@link #onAuditEvent(AuditEvent)} never throws. The {@link
 * WebhookClient} transport is itself best-effort (a blank/unconfigured webhook URL is a no-op and
 * transport failures are swallowed-and-logged), and as belt-and-suspenders the dispatch here is
 * wrapped so any unexpected error is caught and logged rather than propagated into the audit write
 * / Temporal activity. An alert failure must never roll back or block the audit commit.
 *
 * <p>Like {@link KillSwitchAlerter}, the dispatch fires from {@link
 * #onAuditCommitted(AuditEventCommitted)}, a {@code @TransactionalEventListener(phase =
 * AFTER_COMMIT, fallbackExecution = true)} handler, so a slow webhook can never hold the audit
 * {@code @Transactional} commit boundary open. {@code fallbackExecution = true} keeps the
 * no-active-transaction unit-test path firing the listener synchronously.
 */
@Component
public class LivePromotionMissingAlerter {

  private static final Logger log = LoggerFactory.getLogger(LivePromotionMissingAlerter.class);

  /** The single audit kind this alerter pages on. */
  private static final String LIVE_PROMOTION_MISSING_KIND = "LivePromotionMissing";

  private final WebhookClient webhookClient;
  private final TenantWebhookResolver webhookResolver;

  public LivePromotionMissingAlerter(
      WebhookClient webhookClient, TenantWebhookResolver webhookResolver) {
    this.webhookClient = webhookClient;
    this.webhookResolver = webhookResolver;
  }

  /**
   * After-commit entry point. {@code AuditActivitiesImpl.log} publishes an {@link
   * AuditEventCommitted} inside its {@code @Transactional} body; this listener fires only once that
   * transaction commits (or, with {@code fallbackExecution = true}, synchronously when {@code
   * log()} ran without an active transaction — the dsl-less unit-test path).
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onAuditCommitted(AuditEventCommitted committed) {
    onAuditEvent(committed.event());
  }

  /**
   * Builds and dispatches the live-promotion-missing embed for a {@code LivePromotionMissing}
   * event; returns silently for every other kind. Best-effort and non-blocking: never throws.
   * Retained as public for direct unit testing of the dispatch logic.
   */
  public void onAuditEvent(AuditEvent event) {
    try {
      if (event == null
          || event.getKind() == null
          || !LIVE_PROMOTION_MISSING_KIND.equals(event.getKind())) {
        return;
      }
      String url = webhookResolver.resolve(event.getTenantId(), event.getStrategyId());
      webhookClient.postEmbedToUrl(url, buildEmbed(event));
    } catch (RuntimeException e) {
      // Defensive: a notification must never break the audit write / trading path.
      log.warn("live-promotion-missing-alert build/dispatch failed kind={}", safeKind(event), e);
    }
  }

  /**
   * Builds the red live-promotion-missing embed. The {@code reason} (absent / stale / verify_error)
   * is the headline operator-actionable field; {@code broker_target}, {@code signal_id}, and {@code
   * tenant/strategy} round out the page. Every key is read null-safe because a render that throws
   * is swallowed by {@link #onAuditEvent}'s catch — which would SILENTLY LOSE the page that exists
   * to surface a refused real-money entry.
   */
  private WebhookEmbed buildEmbed(AuditEvent event) {
    Map<String, Object> subject = event.getSubject();
    String reason = subjectStr(subject, "reason");

    String title = ":no_entry: Live promotion MISSING — order refused (" + reason + ")";

    List<WebhookEmbed.Field> fields = new ArrayList<>();
    fields.add(new WebhookEmbed.Field("tenant_id", orNa(event.getTenantId()), false));
    fields.add(new WebhookEmbed.Field("strategy_id", orNa(event.getStrategyId()), false));
    fields.add(
        new WebhookEmbed.Field("broker_target", subjectStr(subject, "broker_target"), false));
    fields.add(new WebhookEmbed.Field("reason", reason, false));
    fields.add(new WebhookEmbed.Field("signal_id", subjectStr(subject, "signal_id"), false));

    String footer = "workflow_id: " + orNa(event.getWorkflowId());
    return new WebhookEmbed(title, null, AlertColors.RED, footer, fields);
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
