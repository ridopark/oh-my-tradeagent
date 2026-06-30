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
 * PR #504 follow-up: audit-driven Discord pager for the account-level pct cap's observability
 * events. Mirrors {@link KillSwitchAlerter} — same after-commit {@code @TransactionalEventListener}
 * wiring and non-blocking guarantee — but owns the two account-cap-inactive kinds:
 *
 * <ul>
 *   <li>{@code AccountKillSwitchCapInactive} (RED) — a CONFIGURED account daily-loss pct cap has
 *       failed to arm for N consecutive heartbeats; the portfolio safety net is currently OFF on
 *       (potentially real-money) account, so this MUST be observable/paged.
 *   <li>{@code AccountKillSwitchCapReArmed} (GREEN) — a previously-inactive cap has re-armed
 *       (recovery), so the operator knows the net is back on.
 * </ul>
 *
 * <p>Routing is tenant-scoped via {@link TenantWebhookResolver} (the resolved URL is NEVER logged),
 * exactly like {@link KillSwitchAlerter}; the account cap is tenant-scoped so the strategy_id is
 * the {@code __account__} sentinel and the resolver falls through to the tenant/global webhook.
 *
 * <p>NON-BLOCKING GUARANTEE: {@link #onAuditEvent(AuditEvent)} never throws — a notification must
 * never become a trading-path failure mode, and (critically) a render bug must never silently lose
 * the page that exists to surface a disabled real-money safety net.
 */
@Component
public class AccountKillSwitchCapAlerter {

  private static final Logger log = LoggerFactory.getLogger(AccountKillSwitchCapAlerter.class);

  static final String KIND_CAP_INACTIVE = "AccountKillSwitchCapInactive";
  static final String KIND_CAP_REARMED = "AccountKillSwitchCapReArmed";

  private final WebhookClient webhookClient;
  private final TenantWebhookResolver webhookResolver;

  public AccountKillSwitchCapAlerter(
      WebhookClient webhookClient, TenantWebhookResolver webhookResolver) {
    this.webhookClient = webhookClient;
    this.webhookResolver = webhookResolver;
  }

  /** After-commit entry point — fires only once the audit transaction commits (see #302). */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onAuditCommitted(AuditEventCommitted committed) {
    onAuditEvent(committed.event());
  }

  /**
   * Builds and dispatches the cap-inactive / cap-re-armed embed; returns silently for every other
   * kind. Best-effort and non-blocking: never throws. Public for direct unit testing.
   */
  public void onAuditEvent(AuditEvent event) {
    try {
      if (event == null || event.getKind() == null) {
        return;
      }
      String kind = event.getKind();
      if (!KIND_CAP_INACTIVE.equals(kind) && !KIND_CAP_REARMED.equals(kind)) {
        return;
      }
      String url = webhookResolver.resolve(event.getTenantId(), event.getStrategyId());
      webhookClient.postEmbedToUrl(url, buildEmbed(event, kind));
    } catch (RuntimeException e) {
      log.warn("account-cap-alert build/dispatch failed kind={}", safeKind(event), e);
    }
  }

  private WebhookEmbed buildEmbed(AuditEvent event, String kind) {
    boolean inactive = KIND_CAP_INACTIVE.equals(kind);
    Map<String, Object> subject = event.getSubject();

    String title =
        inactive
            ? ":warning: Account daily-loss cap INACTIVE — portfolio safety net is OFF"
            : ":white_check_mark: Account daily-loss cap RE-ARMED";
    int color = inactive ? AlertColors.RED : AlertColors.GREEN;

    List<WebhookEmbed.Field> fields = new ArrayList<>();
    fields.add(new WebhookEmbed.Field("tenant_id", orNa(event.getTenantId()), false));
    fields.add(new WebhookEmbed.Field("trading_day", subjectStr(subject, "trading_day"), false));
    if (inactive) {
      fields.add(
          new WebhookEmbed.Field(
              "consecutive_inactive_ticks",
              subjectStr(subject, "consecutive_inactive_ticks"),
              false));
    } else {
      fields.add(
          new WebhookEmbed.Field("inactive_ticks", subjectStr(subject, "inactive_ticks"), false));
    }

    String footer = "workflow_id: " + orNa(event.getWorkflowId());
    return new WebhookEmbed(title, null, color, footer, fields);
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
