package com.ohmytradeagent.orchestrator.alert;

import static com.ohmytradeagent.orchestrator.alert.AlertSubjects.signedUnrealizedPnl;
import static com.ohmytradeagent.orchestrator.alert.AlertSubjects.subjectInt;
import static com.ohmytradeagent.orchestrator.alert.AlertSubjects.trimmedSubject;

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

  /**
   * PLAN-2026-07-22: the FIRST deferred tick of a small-book MTM-unavailable blip episode. YELLOW
   * (fail-safe) — the cap is WORKING (it caught a transient quote blip and did NOT trip); this page
   * surfaces a chronic every-other-tick quote degradation instead of letting it hide in a WARN log.
   */
  static final String KIND_MTM_DEFERRED = "AccountKillSwitchMtmDeferred";

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
          && !KIND_STILL_HOLDING.equals(kind)
          && !KIND_MTM_DEFERRED.equals(kind)) {
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
    if (KIND_MTM_DEFERRED.equals(kind)) {
      return buildMtmDeferredEmbed(event, subject);
    }
    if (KIND_STILL_HOLDING.equals(kind)) {
      return buildStillHoldingEmbed(event, subject);
    }
    if (KIND_CAP_INACTIVE.equals(kind)) {
      return buildCapInactiveEmbed(event, subject);
    }

    // KIND_CAP_REARMED (recovery, GREEN).
    List<WebhookEmbed.Field> fields = new ArrayList<>();
    fields.add(new WebhookEmbed.Field("tenant_id", orNa(event.getTenantId()), false));
    fields.add(new WebhookEmbed.Field("trading_day", subjectStr(subject, "trading_day"), false));
    fields.add(
        new WebhookEmbed.Field("inactive_ticks", subjectStr(subject, "inactive_ticks"), false));

    String footer = "workflow_id: " + orNa(event.getWorkflowId());
    return new WebhookEmbed(
        ":white_check_mark: Account daily-loss cap RE-ARMED",
        null,
        AlertColors.GREEN,
        footer,
        fields);
  }

  /**
   * The cap-inactive page (RED). PLAN-2026-07-22: when the workflow named a typed defer {@code
   * reason} AND the tenant HOLDS open positions ({@code open_positions > 0}), ESCALATE to a loud
   * "Account cap NOT protecting &lt;tenant&gt; — &lt;reason&gt;" page so a real-money tenant
   * carrying unprotected risk gets a sharper first page. When the reason is absent or the tenant is
   * flat (or the open-book count could not be probed), fall back to the generic "safety net OFF"
   * wording — both are RED (the net is off either way), but only the escalated one names the tenant
   * + reason.
   */
  private WebhookEmbed buildCapInactiveEmbed(AuditEvent event, Map<String, Object> subject) {
    String reason = trimmedSubject(subject, "reason");
    Integer openPositions = subjectInt(subject, "open_positions");
    boolean unprotected = reason != null && openPositions != null && openPositions > 0;

    String tenant = orNa(event.getTenantId());
    String title =
        unprotected
            ? ":rotating_light: Account cap NOT protecting " + tenant + " — " + reason
            : ":warning: Account daily-loss cap INACTIVE — portfolio safety net is OFF";

    List<WebhookEmbed.Field> fields = new ArrayList<>();
    fields.add(new WebhookEmbed.Field("tenant_id", tenant, false));
    fields.add(new WebhookEmbed.Field("trading_day", subjectStr(subject, "trading_day"), false));
    fields.add(
        new WebhookEmbed.Field(
            "consecutive_inactive_ticks",
            subjectStr(subject, "consecutive_inactive_ticks"),
            false));
    if (reason != null) {
      fields.add(new WebhookEmbed.Field("reason", reason, false));
    }
    if (openPositions != null) {
      fields.add(new WebhookEmbed.Field("open_positions", String.valueOf(openPositions), false));
    }

    String footer = "workflow_id: " + orNa(event.getWorkflowId());
    return new WebhookEmbed(title, null, AlertColors.RED, footer, fields);
  }

  /**
   * PLAN-2026-07-22: the deferred-fail-close page (YELLOW, fail-safe). The account cap caught a
   * transient quote blip on a small book — it did NOT trip, it re-fetched in-tick and is now
   * watching. This is NOT a failure (never RED): its whole job is to surface a chronic
   * every-other-tick quote degradation that would otherwise hide in a WARN log until an eventual
   * trip. The body names it as fail-safe and states that a genuine sustained outage still
   * fail-closes after {@code trip_ticks} consecutive unpriceable ticks.
   */
  private WebhookEmbed buildMtmDeferredEmbed(AuditEvent event, Map<String, Object> subject) {
    String tenant = orNa(event.getTenantId());
    String tripTicks = subjectStr(subject, "trip_ticks");
    String ticks = subjectStr(subject, "consecutive_ticks") + "/" + tripTicks;

    String title =
        ":hourglass_flowing_sand: Account cap deferred a fail-close — quote blip on " + tenant;
    String description =
        "Account book momentarily unpriceable; the cap did NOT trip (transient quote blip) — "
            + "watching, will fail-close if it stays unpriceable for "
            + tripTicks
            + " consecutive ticks.";

    List<WebhookEmbed.Field> fields = new ArrayList<>();
    fields.add(new WebhookEmbed.Field("tenant_id", tenant, false));
    fields.add(new WebhookEmbed.Field("trading_day", subjectStr(subject, "trading_day"), false));
    fields.add(new WebhookEmbed.Field("listed", subjectStr(subject, "listed"), false));
    fields.add(new WebhookEmbed.Field("failures", subjectStr(subject, "failures"), false));
    fields.add(new WebhookEmbed.Field("consecutive_ticks", ticks, false));

    String footer = "workflow_id: " + orNa(event.getWorkflowId());
    return new WebhookEmbed(title, description, AlertColors.YELLOW, footer, fields);
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
