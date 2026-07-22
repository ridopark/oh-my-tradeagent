package com.ohmytradeagent.orchestrator.alert;

import static com.ohmytradeagent.orchestrator.alert.AlertSubjects.signedUnrealizedPnl;

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

  /**
   * Phase 2b (PLAN-2026-07-15, risk C1): the bounded periodic re-page while the account cap stays
   * tripped AND holding open positions (alert-only, no-auto-flatten posture). RED — this is an
   * unresolved real-money exposure the operator must act on.
   */
  static final String KIND_STILL_HOLDING = "AccountKillSwitchStillHolding";

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
      if (!KIND_CAP_INACTIVE.equals(kind)
          && !KIND_CAP_REARMED.equals(kind)
          && !KIND_STILL_HOLDING.equals(kind)) {
        return;
      }
      String url = webhookResolver.resolve(event.getTenantId(), event.getStrategyId());
      webhookClient.postEmbedToUrl(url, buildEmbed(event, kind));
    } catch (RuntimeException e) {
      log.warn("account-cap-alert build/dispatch failed kind={}", safeKind(event), e);
    }
  }

  private WebhookEmbed buildEmbed(AuditEvent event, String kind) {
    Map<String, Object> subject = event.getSubject();
    if (KIND_STILL_HOLDING.equals(kind)) {
      return buildStillHoldingEmbed(event, subject);
    }
    boolean inactive = KIND_CAP_INACTIVE.equals(kind);

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

  /**
   * Phase 2b (risk C1): the still-tripped-and-holding re-page (RED). Carries the open-position
   * count, current MTM (when priceable), and minutes-since-trip so the operator can gauge exposure,
   * with an actionable body line naming the three resolutions (flatten in Alpaca / trip-to-flatten
   * / reset).
   */
  private WebhookEmbed buildStillHoldingEmbed(AuditEvent event, Map<String, Object> subject) {
    String openPositions = subjectStr(subject, "open_positions");
    String openMtm = subjectStr(subject, "open_mtm");
    String minutes = subjectStr(subject, "minutes_since_trip");

    // open_mtm is UNREALIZED P&L ((bid−entry)×qty×100), not a loss amount — render it signed
    // (+$1,551 gain / -$2,500 loss) so an unsigned number can never be misread as underwater.
    String pnl = signedUnrealizedPnl(openMtm);

    String title = ":rotating_light: Account cap STILL tripped — open positions NOT flattened";
    String description =
        "Account cap STILL tripped — "
            + openPositions
            + " open positions, unrealized P&L "
            + pnl
            + ", "
            + minutes
            + " min since trip — flatten manually in Alpaca (or trip-to-flatten), or reset.";

    List<WebhookEmbed.Field> fields = new ArrayList<>();
    fields.add(new WebhookEmbed.Field("tenant_id", orNa(event.getTenantId()), false));
    fields.add(new WebhookEmbed.Field("trading_day", subjectStr(subject, "trading_day"), false));
    fields.add(new WebhookEmbed.Field("open_positions", openPositions, false));
    fields.add(new WebhookEmbed.Field("unrealized P&L", pnl, false));
    fields.add(new WebhookEmbed.Field("minutes_since_trip", minutes, false));

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
