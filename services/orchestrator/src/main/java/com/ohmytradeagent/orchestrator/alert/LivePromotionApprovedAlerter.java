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
 * Audit-driven Discord pager for successful one-click live activations — the GREEN counterpart to
 * {@link KillSwitchAlerter}'s red trip page on one-click deactivate. Mirrors {@link
 * KillSwitchAlerter} / {@link LivePromotionMissingAlerter} EXACTLY (same after-commit
 * {@code @TransactionalEventListener} wiring, same per-tenant {@link TenantWebhookResolver}
 * routing, same non-blocking guarantee) but owns exactly one audit kind: {@code
 * LivePromotionApproved}.
 *
 * <p>Every {@code LivePromotionApproved} event pages GREEN. It is the row {@code
 * LivePromotionActivitiesImpl.activate} writes inside {@code LiveActivationWorkflow.activateLive}
 * (activation_mode {@code one_click}) — one message per Activate — so an operator gets a symmetric
 * "activated live" confirmation on the same per-tenant channel that receives the "Kill switch
 * TRIPPED" message on deactivate.
 *
 * <p>NON-BLOCKING GUARANTEE: {@link #onAuditEvent(AuditEvent)} never throws. The {@link
 * WebhookClient} transport is itself best-effort (a blank/unconfigured webhook URL is a no-op and
 * transport failures are swallowed-and-logged), and as belt-and-suspenders the dispatch here is
 * wrapped so any unexpected error is caught and logged rather than propagated into the audit write
 * / Temporal activity. An alert failure must never roll back or block the audit commit.
 *
 * <p>Like its siblings, the dispatch fires from {@link #onAuditCommitted(AuditEventCommitted)}, a
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)} handler, so a
 * slow webhook can never hold the audit {@code @Transactional} commit boundary open. {@code
 * fallbackExecution = true} keeps the no-active-transaction unit-test path firing synchronously.
 */
@Component
public class LivePromotionApprovedAlerter {

  private static final Logger log = LoggerFactory.getLogger(LivePromotionApprovedAlerter.class);

  /** The single audit kind this alerter pages on. */
  private static final String LIVE_PROMOTION_APPROVED_KIND = "LivePromotionApproved";

  private final WebhookClient webhookClient;
  private final TenantWebhookResolver webhookResolver;

  public LivePromotionApprovedAlerter(
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
   * Builds and dispatches the green live-activation embed for a {@code LivePromotionApproved}
   * event; returns silently for every other kind. Best-effort and non-blocking: never throws.
   * Retained as public for direct unit testing of the dispatch logic.
   */
  public void onAuditEvent(AuditEvent event) {
    try {
      if (event == null
          || event.getKind() == null
          || !LIVE_PROMOTION_APPROVED_KIND.equals(event.getKind())) {
        return;
      }
      String url = webhookResolver.resolve(event.getTenantId(), event.getStrategyId());
      webhookClient.postEmbedToUrl(url, buildEmbed(event));
    } catch (RuntimeException e) {
      // Defensive: a notification must never break the audit write / trading path.
      log.warn("live-promotion-approved-alert build/dispatch failed kind={}", safeKind(event), e);
    }
  }

  /**
   * Builds the green live-activation embed. Fields are the keys {@code
   * LivePromotionActivitiesImpl.activate} actually writes into the {@code LivePromotionApproved}
   * subject: {@code operator_id}, {@code broker_target}, {@code expected_account_id}, {@code
   * activation_mode}. Every key is read null-safe because a render that throws is swallowed by
   * {@link #onAuditEvent}'s catch — which would SILENTLY LOSE the confirmation.
   */
  private WebhookEmbed buildEmbed(AuditEvent event) {
    Map<String, Object> subject = event.getSubject();

    String title = ":white_check_mark: Strategy activated live";

    List<WebhookEmbed.Field> fields = new ArrayList<>();
    fields.add(new WebhookEmbed.Field("tenant_id", orNa(event.getTenantId()), false));
    fields.add(new WebhookEmbed.Field("strategy_id", orNa(event.getStrategyId()), false));
    fields.add(
        new WebhookEmbed.Field("broker_target", subjectStr(subject, "broker_target"), false));
    fields.add(new WebhookEmbed.Field("operator_id", subjectStr(subject, "operator_id"), false));
    fields.add(
        new WebhookEmbed.Field(
            "expected_account_id", subjectStr(subject, "expected_account_id"), false));
    fields.add(
        new WebhookEmbed.Field("activation_mode", subjectStr(subject, "activation_mode"), false));

    String footer = "workflow_id: " + orNa(event.getWorkflowId());
    return new WebhookEmbed(title, null, AlertColors.GREEN, footer, fields);
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
