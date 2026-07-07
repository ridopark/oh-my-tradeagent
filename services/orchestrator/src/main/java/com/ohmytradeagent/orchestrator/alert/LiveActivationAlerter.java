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
 * Audit-driven Discord pager for a successful one-click live activation — the GREEN counterpart to
 * {@link KillSwitchAlerter}'s red trip page on one-click deactivate. Mirrors {@link
 * KillSwitchAlerter} / {@link LivePromotionMissingAlerter} EXACTLY (same after-commit
 * {@code @TransactionalEventListener} wiring, same per-tenant {@link TenantWebhookResolver}
 * routing, same non-blocking guarantee).
 *
 * <p>Fires on the {@code KillSwitchResetApproved} audit row WHERE subject {@code via ==
 * "live_activation"}. That row is written by {@code KillSwitchWorkflowImpl.resetOnActivation},
 * which runs LAST in {@code LiveActivationWorkflow.activateLive} (after {@code promotion.activate})
 * and only commits when the kill-switch UNTRIP actually succeeded — so the green "activated"
 * message is HONEST: no false-success window. If the reset fails the strategy stays halted and no
 * green alert fires; if it succeeds exactly one green alert fires and the strategy is genuinely
 * live.
 *
 * <p>The {@code via == "live_activation"} filter is what distinguishes this from a manual
 * dual-control reset ({@code reset_killswitch}), which writes the SAME {@code
 * KillSwitchResetApproved} kind but WITHOUT a {@code via} key — so a manual reset never triggers
 * this pager. {@link KillSwitchAlerter} only pages on {@code KillSwitchTripped} (it has a
 * no-dispatch branch for every other kind, resets included), so exactly ONE green message fires for
 * an activation reset.
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
public class LiveActivationAlerter {

  private static final Logger log = LoggerFactory.getLogger(LiveActivationAlerter.class);

  /** The audit kind this alerter inspects. */
  private static final String KILL_SWITCH_RESET_APPROVED_KIND = "KillSwitchResetApproved";

  /** The subject marker that scopes the page to the one-click activation reset ONLY. */
  private static final String VIA_KEY = "via";

  private static final String VIA_LIVE_ACTIVATION = "live_activation";

  private final WebhookClient webhookClient;
  private final TenantWebhookResolver webhookResolver;

  public LiveActivationAlerter(WebhookClient webhookClient, TenantWebhookResolver webhookResolver) {
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
   * Builds and dispatches the green live-activation embed for a {@code KillSwitchResetApproved}
   * event whose subject carries {@code via == "live_activation"}; returns silently for every other
   * kind AND for a manual dual-control reset (no {@code via}). Best-effort and non-blocking: never
   * throws. Retained as public for direct unit testing of the dispatch logic.
   */
  public void onAuditEvent(AuditEvent event) {
    try {
      if (event == null
          || event.getKind() == null
          || !KILL_SWITCH_RESET_APPROVED_KIND.equals(event.getKind())) {
        return;
      }
      Map<String, Object> subject = event.getSubject();
      if (subject == null || !VIA_LIVE_ACTIVATION.equals(String.valueOf(subject.get(VIA_KEY)))) {
        // Manual dual-control reset (no via) or any other reset — not an activation.
        return;
      }
      String url = webhookResolver.resolve(event.getTenantId(), event.getStrategyId());
      webhookClient.postEmbedToUrl(url, buildEmbed(event));
    } catch (RuntimeException e) {
      // Defensive: a notification must never break the audit write / trading path.
      log.warn("live-activation-alert build/dispatch failed kind={}", safeKind(event), e);
    }
  }

  /**
   * Builds the green live-activation embed. Fields are the keys the {@code resetOnActivation} audit
   * subject actually carries ({@code operator}, {@code cooling_down_until}) plus the {@code
   * tenant_id}/{@code strategy_id} audit-row columns. Every key is read null-safe because a render
   * that throws is swallowed by {@link #onAuditEvent}'s catch — which would SILENTLY LOSE the
   * confirmation.
   */
  private WebhookEmbed buildEmbed(AuditEvent event) {
    Map<String, Object> subject = event.getSubject();

    String title = ":white_check_mark: Strategy activated live";

    List<WebhookEmbed.Field> fields = new ArrayList<>();
    fields.add(new WebhookEmbed.Field("tenant_id", orNa(event.getTenantId()), false));
    fields.add(new WebhookEmbed.Field("strategy_id", orNa(event.getStrategyId()), false));
    fields.add(new WebhookEmbed.Field("operator", subjectStr(subject, "operator"), false));
    fields.add(
        new WebhookEmbed.Field(
            "cooling_down_until", subjectStr(subject, "cooling_down_until"), false));

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
