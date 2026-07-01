package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.AccountKillSwitchWorkflowInput;
import com.ohmytradeagent.contract.AccountSnapshotRequest;
import com.ohmytradeagent.contract.AccountSnapshotResult;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.GetOptionQuoteRequest;
import com.ohmytradeagent.contract.KillSwitchState;
import com.ohmytradeagent.contract.OptionQuoteResult;
import com.ohmytradeagent.contract.ResetKillSwitchRequest;
import com.ohmytradeagent.contract.TripKillSwitchRequest;
import com.ohmytradeagent.contract.activities.AccountSnapshotActivity;
import com.ohmytradeagent.contract.activities.DailyPnlExecActivity;
import com.ohmytradeagent.orchestrator.activities.AccountKillSwitchCascadeActivities;
import com.ohmytradeagent.orchestrator.activities.AccountOpenBook;
import com.ohmytradeagent.orchestrator.activities.AccountOpenBook.OpenPositionValuation;
import com.ohmytradeagent.orchestrator.activities.AccountPnlActivities;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.GetOptionQuoteActivity;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import com.ohmytradeagent.orchestrator.activities.TenantConfigActivities;
import com.ohmytradeagent.orchestrator.activities.TenantStrategyBrokerTarget;
import com.ohmytradeagent.orchestrator.domain.Sizing;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.TemporalFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInit;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 6 account-level kill-switch impl. Lives on the {@code orchestrator-core} task queue. One
 * per tenant. Mirrors {@link KillSwitchWorkflowImpl} (heartbeat loop, market-hours gate,
 * dual-control trip/reset, continueAsNew, audit) but the trip predicate is the TENANT-WIDE total:
 *
 * <pre>totalPnl = tenantRealizedPnl + tenantOpenMtm ; trip when totalPnl &lt;= -threshold</pre>
 *
 * <p><b>Open MTM.</b> The {@link AccountPnlActivities#accountOpenBook} Activity returns the
 * tenant's whole running book (positionState fields + the #325 fail-closed counts); this workflow
 * values each position's UNREALIZED loss as {@code (liveBid - entryPremium) * remainingQty * 100},
 * sourcing {@code liveBid} from {@link GetOptionQuoteActivity#getOptionQuote} (the quote stub can
 * only be dispatched from workflow code, which is why the quote loop lives here, not in the
 * Activity).
 *
 * <p><b>Fail-CLOSED MTM.</b> Quote outages must NOT silently disable the cap. We reuse the {@link
 * VisibilityPortfolioSnapshot} #325 precedent — the relative {@code >50%} threshold plus the
 * small-book floor — applied to the COMBINED failure count (positionState query failures +
 * option-quote {@code UNAVAILABLE}/{@code FAILED}) over the listed book. Below the bound, an
 * unquotable position is skipped (best-effort, isolated outage). At/above the bound (a correlated
 * Visibility/market-data degradation that would otherwise under-count the loss and fail-OPEN) the
 * heartbeat fails CLOSED by tripping with the distinct reason {@code auto:account_mtm_unavailable}
 * (the cap engages rather than reporting a falsely-small loss). The threshold-unset path is checked
 * FIRST, so an opted-out tenant stays fully inert even during a quote outage.
 *
 * <p>Opt-in / inert: a null threshold => no trip ever (the heartbeat returns before computing PnL).
 *
 * <p>Determinism: all time from {@link Workflow#currentTimeMillis()}; all randomness from {@link
 * Workflow#randomUUID()}; the Visibility/quote reads run inside Activities (never in workflow
 * code).
 */
public class AccountKillSwitchWorkflowImpl implements AccountKillSwitchWorkflow {

  private static final String KIND_KILL_SWITCH_TRIPPED = "KillSwitchTripped";
  private static final String KIND_KILL_SWITCH_RESET_APPROVED = "KillSwitchResetApproved";
  private static final String KIND_KILL_SWITCH_HEARTBEAT_ERROR = "KillSwitchHeartbeatError";

  /**
   * Audit kind emitted (and Discord-paged via {@code AccountKillSwitchCapAlerter}) when a
   * configured pct cap has FAILED TO ARM for {@link #INACTIVE_ALERT_TICKS} consecutive heartbeats —
   * i.e. the portfolio safety net is currently OFF on real money. Observability for the "silently
   * disabled cap" gap.
   */
  private static final String KIND_ACCOUNT_CAP_INACTIVE = "AccountKillSwitchCapInactive";

  /** Audit kind emitted (and paged) when a previously-inactive configured pct cap re-arms. */
  private static final String KIND_ACCOUNT_CAP_REARMED = "AccountKillSwitchCapReArmed";

  /** Audit strategy_id sentinel — the account cap is tenant-scoped, not strategy-scoped. */
  static final String ACCOUNT_SCOPE = "__account__";

  /**
   * Version gate for the start-of-day-equity pct cap. ALL new commands (the {@code
   * accountDailyLossPct} read, the {@code tenantBrokerTarget} read, and the {@code
   * AccountSnapshotActivity} SOD-equity dispatch + the pct threshold resolution) are strictly
   * behind {@code v >= 1}. At {@link Workflow#DEFAULT_VERSION} the heartbeat replays the pre-change
   * absolute-threshold command stream BYTE-IDENTICALLY (no SOD-equity read, no new marker). Pinned
   * by {@code AccountKillSwitchWorkflowImplLegacyReplayTest}.
   */
  static final String VERSION_ACCOUNT_DAILY_LOSS_PCT = "account-daily-loss-pct-of-sod-equity-v1";

  /**
   * Version gate for the cap-inactive observability alert. ALL new commands (the {@code
   * AccountKillSwitchCapInactive} / {@code AccountKillSwitchCapReArmed} audit emits) are strictly
   * behind {@code v >= 1}; the consecutive-inactive counter is pure workflow state (no command). At
   * {@link Workflow#DEFAULT_VERSION} the heartbeat replays byte-identically. Pinned by {@code
   * AccountKillSwitchWorkflowImplLegacyReplayTest}.
   */
  static final String VERSION_CAP_INACTIVE_ALERT = "account-cap-inactive-alert-v1";

  /**
   * Phase 2 (PLAN-2026-06-30) gate for re-sourcing tenant realized P&amp;L from the exec {@code
   * order_intent_journal} (broker truth) instead of {@code audit_log}. At {@code v>=1} the
   * heartbeat resolves each strategy's {@code broker_target} and routes a per-strategy realized
   * read to that strategy's {@code broker-<target>} {@link DailyPnlExecActivity}, summing them
   * (fail CLOSED if any per-strategy read fails — guardrail G2). At {@link
   * Workflow#DEFAULT_VERSION} it calls the legacy {@code
   * AccountPnlActivities.computeTenantRealizedPnl} ({@code audit_log}) — byte-identical to the
   * legacy replay path. Same string as {@code KillSwitchWorkflowImpl} (independent history).
   */
  static final String VERSION_KILLSWITCH_REALIZED_FROM_EXEC =
      "killswitch-realized-from-exec-journal-v1";

  /**
   * Audit kind emitted when the exec-realized read has been unavailable for too many ticks (G1).
   */
  private static final String KIND_REALIZED_READ_UNAVAILABLE = "KillSwitchRealizedReadUnavailable";

  /**
   * Consecutive account-realized-read failures the heartbeat tolerates before paging (guardrail
   * G1). A failed/fail-closed read defers the whole tick (never a spurious trip) and feeds the
   * inactive counter via {@link #run}'s not-armed path; this dedicated counter drives ONE distinct
   * bounded alert. Package-private for test override.
   */
  static int REALIZED_READ_FAILURE_ALERT_TICKS = 3;

  static final String MARKET_DATA_TASK_QUEUE = "market-data";

  static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(60);
  static final long DEFAULT_RESET_COOLDOWN_SECS = 60L;

  /**
   * Consecutive heartbeats a configured pct cap may fail to arm before we page. 3 ticks at the 60s
   * {@link #HEARTBEAT_INTERVAL} = ~3 min — fast enough that "the portfolio safety net is OFF"
   * surfaces well within a session, but past a single transient broker/equity blip (one failed
   * AccountSnapshot retried-then-deferred tick) so we do not page on noise. Package-private for
   * test override.
   */
  static int INACTIVE_ALERT_TICKS = 3;

  /**
   * While the cap stays inactive, re-page at most once every this many ticks (30 ticks * 60s = ~30
   * min) so a persistent outage keeps nagging without spamming a page every minute.
   */
  static int INACTIVE_REPAGE_TICKS = 30;

  /**
   * See {@link KillSwitchWorkflowImpl#historyLengthWatermark}. Package-private for test override.
   */
  static long historyLengthWatermark = 10_000L;

  /** #325 fail-closed bound parameters (mirrors VisibilityPortfolioSnapshot). */
  private static final int RELATIVE_FAILURE_THRESHOLD_MULTIPLIER = 2;

  private static final int SMALL_BOOK_MAX_POSITIONS = 2;

  private static final BigDecimal CONTRACT_MULTIPLIER = Sizing.CONTRACT_MULTIPLIER;

  private static final ActivityOptions DEFAULT_OPTIONS =
      ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build();
  private static final ActivityOptions CASCADE_OPTIONS =
      ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build();
  private static final ActivityOptions QUOTE_OPTIONS =
      ActivityOptions.newBuilder()
          .setTaskQueue(MARKET_DATA_TASK_QUEUE)
          .setStartToCloseTimeout(Duration.ofSeconds(5))
          .build();

  private final AuditActivities audit =
      Workflow.newActivityStub(AuditActivities.class, DEFAULT_OPTIONS);
  private final MarketCalendarActivities calendar =
      Workflow.newActivityStub(MarketCalendarActivities.class, DEFAULT_OPTIONS);
  private final TenantConfigActivities tenantConfig =
      Workflow.newActivityStub(TenantConfigActivities.class, DEFAULT_OPTIONS);
  private final AccountPnlActivities accountPnl =
      Workflow.newActivityStub(AccountPnlActivities.class, DEFAULT_OPTIONS);
  private final AccountKillSwitchCascadeActivities cascade =
      Workflow.newActivityStub(AccountKillSwitchCascadeActivities.class, CASCADE_OPTIONS);
  private final GetOptionQuoteActivity optionQuote =
      Workflow.newActivityStub(GetOptionQuoteActivity.class, QUOTE_OPTIONS);

  private final AccountKillSwitchWorkflowInput input;
  private boolean tripped;
  private String reason = "";
  private String actor = "";
  private OffsetDateTime trippedAt;
  private OffsetDateTime coolingDownUntil;
  private LocalDate tradingDay;

  /**
   * Start-of-day account equity for {@link #tradingDay}. Captured ONCE per trading day at the
   * rollover (lazily, on the first heartbeat that needs it) and carried across continue-as-new so
   * it survives a CAN within the same day. {@code null} = not yet captured (or capture failed / pct
   * cap not configured), in which case the pct check is DEFERRED.
   */
  private BigDecimal sodEquity;

  /**
   * Cap-inactive observability state (deterministic workflow state, no commands). {@code
   * consecutiveInactiveTicks} counts heartbeats where a configured pct cap failed to arm (defer OR
   * caught heartbeat error). {@code capInactiveAlerted} is true once we have paged for the current
   * inactive episode (so we page once on entry, not every tick). {@code ticksSinceInactiveAlert}
   * drives the re-page throttle. {@code pctConfiguredLastSeen} caches the last successful read of
   * "is pct configured?" ({@code null} = never read) so a heartbeat that threw BEFORE reading
   * config (e.g. a ConfigMap-typo parse error mid-session) is still attributed to a configured-cap
   * tenant; best-effort attribution — an unknown ({@code null}) config state never counts toward an
   * alert.
   *
   * <p>This state is intentionally NOT carried across continue-as-new ({@code
   * buildCarryForwardInput} does not thread it): an in-flight inactive episode re-accumulates from
   * zero after a CAN. Accepted because the cap-inactive signal is observability-only (a re-page
   * after an infrequent CAN is mild under-paging) and threading it would force another
   * carry-forward schema bump for no safety gain.
   */
  private int consecutiveInactiveTicks;

  private boolean capInactiveAlerted;
  private int ticksSinceInactiveAlert;
  private Boolean pctConfiguredLastSeen;

  /**
   * Guardrail G1 (Phase 2 exec-realized re-source): consecutive account-realized-read failures on
   * the {@code v>=1} path (deterministic workflow state, no commands). A failed / fail-closed read
   * defers the whole tick (never a spurious trip) and increments this; on crossing {@link
   * #REALIZED_READ_FAILURE_ALERT_TICKS} the heartbeat emits ONE bounded alert (distinct reason). A
   * good tenant-realized read clears both. Not carried across continue-as-new (observability-only).
   */
  private int consecutiveRealizedReadFailures;

  private boolean realizedReadUnavailableAlerted;

  @WorkflowInit
  public AccountKillSwitchWorkflowImpl(AccountKillSwitchWorkflowInput in) {
    if (in.getSchemaVersion() == null || in.getSchemaVersion() > 3L) {
      throw new IllegalArgumentException(
          "AccountKillSwitchWorkflowInput schema_version unsupported: " + in.getSchemaVersion());
    }
    this.input = in;
    if (Boolean.TRUE.equals(in.getTripped())) {
      this.tripped = true;
    }
    if (in.getReason() != null) {
      this.reason = in.getReason();
    }
    if (in.getActor() != null) {
      this.actor = in.getActor();
    }
    if (in.getTrippedAt() != null) {
      this.trippedAt = in.getTrippedAt();
    }
    if (in.getCoolingDownUntil() != null) {
      this.coolingDownUntil = in.getCoolingDownUntil();
    }
    if (in.getTradingDay() != null) {
      this.tradingDay = in.getTradingDay();
    }
    if (in.getSodEquity() != null) {
      this.sodEquity = in.getSodEquity();
    }
  }

  @Override
  public String run(AccountKillSwitchWorkflowInput in) {
    if (this.tradingDay == null) {
      this.tradingDay = calendar.todayEt();
    }
    while (true) {
      Workflow.sleep(HEARTBEAT_INTERVAL);
      // armed == true: the cap evaluated this tick (it resolved a threshold, tripped, was already
      // tripped/cooling/closed, or the pct cap is intentionally not configured). armed == false: a
      // configured pct cap failed to arm (defer) — the inactive condition. A thrown heartbeat is
      // also treated as not-armed below: the cap was not evaluated this tick.
      boolean armed = true;
      try {
        armed = heartbeat();
      } catch (RuntimeException e) {
        armed = false;
        auditLog(
            KIND_KILL_SWITCH_HEARTBEAT_ERROR,
            subject("error", e.getMessage(), "trading_day", tradingDay));
      }
      // Cap-inactive observability (v>=1 only): page when a configured pct cap stays unarmed.
      // Read the gate once per tick at this stable scope; at DEFAULT_VERSION nothing new is
      // emitted.
      int alertVersion =
          Workflow.getVersion(VERSION_CAP_INACTIVE_ALERT, Workflow.DEFAULT_VERSION, 1);
      if (alertVersion >= 1) {
        recordInactivityOutcome(armed);
      }
      if (Workflow.getInfo().getHistoryLength() > historyLengthWatermark) {
        Workflow.continueAsNew(buildCarryForwardInput());
      }
    }
  }

  /**
   * Cap-inactive observability bookkeeping (v&gt;=1). {@code armed} is the heartbeat outcome (or
   * {@code false} when the heartbeat threw). The counter advances only when the cap is configured
   * (so an opt-out tenant never pages); it pages ONCE on crossing {@link #INACTIVE_ALERT_TICKS},
   * re-pages at most every {@link #INACTIVE_REPAGE_TICKS} while still inactive, and emits a re-arm
   * audit on recovery. All audit emits are commands gated by the caller's version check; the
   * counters are pure workflow state.
   */
  private void recordInactivityOutcome(boolean armed) {
    if (armed) {
      // Cap evaluated (or intentionally off). If we had paged for an inactive episode, announce the
      // recovery and reset; otherwise just clear the counters.
      if (capInactiveAlerted) {
        auditLog(
            KIND_ACCOUNT_CAP_REARMED,
            subject(
                "trading_day",
                tradingDay,
                "inactive_ticks",
                consecutiveInactiveTicks,
                "scope",
                "account"));
      }
      consecutiveInactiveTicks = 0;
      capInactiveAlerted = false;
      ticksSinceInactiveAlert = 0;
      return;
    }
    // Not armed. Only an UNARMED-WHILE-CONFIGURED tick is an "inactive cap" condition worth paging.
    // pctConfiguredLastSeen is the last successful read (updated in heartbeat(); null = never
    // read);
    // a tick that threw before reading config falls back to the last-known value — a persistent
    // ConfigMap typo that throws every tick was, by definition, configured the moment before it
    // broke. A null (never-read) state never counts toward an alert (fails safe: under-page).
    if (!Boolean.TRUE.equals(pctConfiguredLastSeen)) {
      return;
    }
    consecutiveInactiveTicks++;
    if (capInactiveAlerted) {
      ticksSinceInactiveAlert++;
      if (ticksSinceInactiveAlert >= INACTIVE_REPAGE_TICKS) {
        emitCapInactive();
        ticksSinceInactiveAlert = 0;
      }
      return;
    }
    if (consecutiveInactiveTicks >= INACTIVE_ALERT_TICKS) {
      emitCapInactive();
      capInactiveAlerted = true;
      ticksSinceInactiveAlert = 0;
    }
  }

  private void emitCapInactive() {
    auditLog(
        KIND_ACCOUNT_CAP_INACTIVE,
        subject(
            "trading_day",
            tradingDay,
            "consecutive_inactive_ticks",
            consecutiveInactiveTicks,
            "scope",
            "account"));
  }

  private AccountKillSwitchWorkflowInput buildCarryForwardInput() {
    return carryForwardInput(
        input.getTenantId(),
        tripped,
        reason,
        actor,
        trippedAt,
        coolingDownUntil,
        tradingDay,
        sodEquity);
  }

  /**
   * Pure builder for the continue-as-new carry-forward input. Package-private + static so it can be
   * unit-tested for the rolling-deploy schema_version branching without a Temporal context.
   *
   * <p>Rolling-deploy safety: only stamp v3 when we actually carry sod_equity. A pre-v3 worker
   * validates {@code schema_version <= 2} at {@code @WorkflowInit} and throws on a v3 input — so an
   * unconditional v3 bump would wedge any execution that continues-as-new on a new pod and is then
   * picked up by an old pod mid rollout/canary. A carry-forward WITHOUT a captured SOD equity is
   * byte-identical to the legacy v2 shape, so stamp v2 and stay old-worker-compatible. An execution
   * that HAS captured sodEquity is already pinned to {@code v>=1} by the getVersion marker (an old
   * worker cannot replay it anyway), so v3 is correct there.
   */
  static AccountKillSwitchWorkflowInput carryForwardInput(
      String tenantId,
      boolean tripped,
      String reason,
      String actor,
      OffsetDateTime trippedAt,
      OffsetDateTime coolingDownUntil,
      LocalDate tradingDay,
      BigDecimal sodEquity) {
    AccountKillSwitchWorkflowInput carry = new AccountKillSwitchWorkflowInput();
    carry.setSchemaVersion(sodEquity != null ? 3L : 2L);
    carry.setTenantId(tenantId);
    carry.setTripped(tripped);
    if (reason != null && !reason.isEmpty()) {
      carry.setReason(reason);
    }
    if (actor != null && !actor.isEmpty()) {
      carry.setActor(actor);
    }
    carry.setTrippedAt(trippedAt);
    carry.setCoolingDownUntil(coolingDownUntil);
    carry.setTradingDay(tradingDay);
    // Carry SOD equity so a same-day CAN does not re-read it (and a different-day CAN re-snapshots
    // at the next rollover regardless). Null when not captured.
    if (sodEquity != null) {
      carry.setSodEquity(sodEquity);
    }
    return carry;
  }

  /**
   * One heartbeat tick. Returns {@code true} when the cap was ARMED or is intentionally off
   * (already tripped, cooling down, market closed, or no pct cap configured) and {@code false} when
   * a CONFIGURED pct cap failed to arm (the deferral path) — the cap-inactive condition the
   * observability alert pages on. A thrown heartbeat is treated as not-armed by {@link #run}.
   */
  private boolean heartbeat() {
    // Read the version gate ONCE, at a stable scope, before any branch. At DEFAULT_VERSION every
    // new command below is skipped, so a pre-change in-flight history replays byte-identically.
    // TestWorkflowEnvironment always reports v==1 for fresh workflows; the v==DEFAULT_VERSION
    // branch is exercised only by AccountKillSwitchWorkflowImplLegacyReplayTest against a recorded
    // pre-marker history.
    int pctVersion =
        Workflow.getVersion(VERSION_ACCOUNT_DAILY_LOSS_PCT, Workflow.DEFAULT_VERSION, 1);
    // Phase 2: read the realized-source gate ONCE, at a stable scope, before any branch. At
    // DEFAULT_VERSION the legacy audit_log path runs (byte-identical replay); v>=1 routes
    // per-strategy broker-truth reads.
    int realizedVersion =
        Workflow.getVersion(VERSION_KILLSWITCH_REALIZED_FROM_EXEC, Workflow.DEFAULT_VERSION, 1);

    LocalDate today = calendar.todayEt();
    if (!today.equals(tradingDay)) {
      this.tradingDay = today;
      // New trading day => the prior SOD-equity snapshot is stale; re-capture lazily below.
      this.sodEquity = null;
    }
    if (tripped) {
      return true; // cap already engaged — not an inactive condition.
    }
    // Post-reset cooldown: after a reset the cap stays inert until coolingDownUntil so a still-down
    // book does not immediately re-trip the just-reset switch within the same window.
    if (coolingDownUntil != null && workflowNow().isBefore(coolingDownUntil)) {
      return true; // intentional inert window — not a cap-inactive condition.
    }
    if (!calendar.isMarketOpen()) {
      return true; // cap does not evaluate off-hours by design — not an inactive condition.
    }

    // Effective-threshold resolution (precedence): pct x SOD-equity wins when configured AND
    // resolvable; otherwise fall back to the absolute account_daily_loss_threshold; if neither
    // resolves the cap is inert (no trip), exactly as before this change. All pct machinery is
    // gated behind v>=1 so the legacy absolute-only path is unchanged at DEFAULT_VERSION.
    BigDecimal absolute = tenantConfig.accountDailyLossThreshold(input.getTenantId());
    BigDecimal threshold = absolute;
    boolean pctConfigured = false;
    if (pctVersion >= 1) {
      BigDecimal pct = tenantConfig.accountDailyLossPct(input.getTenantId());
      pctConfigured = pct != null && pct.signum() > 0;
      // Cache the last successful "is pct configured?" read so run()'s inactivity bookkeeping can
      // attribute a later parse-throwing tick to a configured-cap tenant.
      this.pctConfiguredLastSeen = pctConfigured;
      threshold = resolveEffectiveThreshold(absolute, pct);
    }
    // Opt-in / inert: no resolvable threshold => no trip ever. Checked FIRST so an opted-out tenant
    // is fully inert even during a Visibility/quote outage. A pct-configured tenant that lands here
    // is DEFERRING (SOD equity unavailable) with no absolute fallback => cap NOT armed (inactive).
    if (threshold == null || threshold.signum() <= 0) {
      return !pctConfigured;
    }

    BigDecimal realized;
    if (realizedVersion >= 1) {
      // Broker truth: sum a per-strategy realized read routed to each strategy's broker-<target>
      // exec queue. A null return means the tick was DEFERRED (a per-strategy read failed, or a
      // strategy could not be routed) — fail CLOSED: do not trip on a partial/unknown realized
      // number (guardrail G2). The deferral feeds the inactive counter (armed=false) and the
      // dedicated realized-read alert.
      realized = execTenantRealized();
      if (realized == null) {
        return false; // cap NOT armed this tick — never trips on a missing/partial number.
      }
      consecutiveRealizedReadFailures = 0;
      realizedReadUnavailableAlerted = false;
    } else {
      // DEFAULT_VERSION: legacy audit_log path — byte-identical to the pre-Phase-2 replay stream.
      realized = accountPnl.computeTenantRealizedPnl(input.getTenantId(), tradingDay);
    }
    AccountOpenBook book = accountPnl.accountOpenBook(input.getTenantId());

    BigDecimal openMtm = BigDecimal.ZERO;
    int quoteFailures = 0;
    for (OpenPositionValuation pos : book.positions()) {
      BigDecimal bid = liveBid(pos.contractSymbol());
      if (bid == null) {
        quoteFailures++;
        continue;
      }
      // Unrealized P&L per the cap definition: (liveBid - entryPremium) * remainingQty * 100.
      BigDecimal perContract = bid.subtract(pos.entryPremium());
      openMtm =
          openMtm.add(
              perContract
                  .multiply(BigDecimal.valueOf(pos.remainingQty()))
                  .multiply(CONTRACT_MULTIPLIER));
    }

    // Fail-CLOSED: a correlated positionState + quote outage that drops too many listed positions
    // must NOT under-count the loss (fail-OPEN). Mirror the #325 relative >50% / small-book bound
    // over the COMBINED failure count. At/above the bound, engage the cap rather than trust the
    // (falsely small) computed loss.
    int combinedFailures = book.valueFailures() + quoteFailures;
    if (book.listed() > 0 && failsClosed(book.listed(), combinedFailures)) {
      doTrip("auto:account_mtm_unavailable", "auto:account_mtm_unavailable", null);
      return true; // cap engaged (fail-closed trip) — armed.
    }

    BigDecimal totalPnl = realized.add(openMtm);
    if (totalPnl.compareTo(threshold.negate()) <= 0) {
      doTrip("auto:account_daily_loss", "auto:account_daily_loss", totalPnl);
    }
    // Threshold resolved and the loss was evaluated against it — the cap is ARMED this tick.
    return true;
  }

  /**
   * Phase 2 (v&gt;=1): tenant-wide realized P&amp;L summed from broker-truth per-strategy reads.
   * The strategy list + per-strategy {@code broker_target} come from an Activity ({@code
   * tenantStrategyBrokerTargets}); the routed {@link DailyPnlExecActivity} stub per strategy is
   * built HERE, in workflow code, because {@code ExecActivitiesFactory.taskQueueFor} is workflow-
   * bound (the Spring bean has no Workflow context). Supports mixed broker_target tenants.
   *
   * <p><b>Guardrail G2 — account partial-sum FORBIDDEN, fail CLOSED.</b> If ANY per-strategy read
   * fails (exec down / queue backpressure / unroutable-bare broker_target) this returns {@code
   * null} (deferral) rather than defaulting that strategy to zero and summing a partial — a partial
   * would UNDER-count the loss and let the account cap under-protect. Also matches the existing
   * accountOpenBook propagate-don't-swallow discipline. The null deferral routes into G1 (skip-tick
   * + retry-in-options + bounded alert), never a spurious trip.
   */
  private BigDecimal execTenantRealized() {
    List<TenantStrategyBrokerTarget> strategies;
    try {
      strategies = accountPnl.tenantStrategyBrokerTargets(input.getTenantId());
    } catch (TemporalFailure e) {
      // The resolver throws fail-closed on an empty strategy set; treat any failure as a deferral.
      recordRealizedReadFailure(null, e.getMessage());
      return null;
    }
    BigDecimal total = BigDecimal.ZERO;
    for (TenantStrategyBrokerTarget s : strategies) {
      try {
        total =
            total.add(
                execRealized(s.brokerTarget())
                    .computeRealizedPnl(input.getTenantId(), s.strategyId(), tradingDay));
      } catch (TemporalFailure e) {
        // FAIL CLOSED (G2): a single unroutable/unavailable strategy defers the WHOLE tenant
        // compute
        // — never sum a partial. taskQueueFor(...) throws a non-retryable ApplicationFailure (a
        // TemporalFailure) synchronously on a null/bare broker_target, so an unroutable strategy is
        // caught here too.
        recordRealizedReadFailure(s.brokerTarget(), e.getMessage());
        return null;
      }
    }
    return total;
  }

  /**
   * Builds the broker_target-routed {@link DailyPnlExecActivity} stub in WORKFLOW code (guardrail
   * G1 activity options: 12s start-to-close &lt; the 60s heartbeat cadence, up to 3 bounded retries
   * under a ~40s schedule-to-close ceiling, so a transient exec blip is absorbed by retry within
   * the tick; a genuine outage exhausts the budget and surfaces as a {@link TemporalFailure} the
   * caller turns into a deferred tick + bounded alert — never a trip). {@code taskQueueFor} is
   * deterministic/replay-safe.
   */
  private DailyPnlExecActivity execRealized(String brokerTarget) {
    return Workflow.newActivityStub(
        DailyPnlExecActivity.class,
        ActivityOptions.newBuilder()
            .setTaskQueue(ExecActivitiesFactory.taskQueueFor(brokerTarget))
            .setStartToCloseTimeout(Duration.ofSeconds(12))
            .setScheduleToCloseTimeout(Duration.ofSeconds(40))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
            .build());
  }

  /**
   * Guardrail G1 bookkeeping for a deferred (failed/fail-closed) account-realized read: increments
   * the consecutive counter and, on crossing {@link #REALIZED_READ_FAILURE_ALERT_TICKS}, emits ONE
   * bounded alert with a DISTINCT reason (mirrors the {@code auto:account_mtm_unavailable}
   * precedent shape). Never calls {@code doTrip} — a missing/partial number is not a loss.
   */
  private void recordRealizedReadFailure(String brokerTarget, String err) {
    consecutiveRealizedReadFailures++;
    if (!realizedReadUnavailableAlerted
        && consecutiveRealizedReadFailures >= REALIZED_READ_FAILURE_ALERT_TICKS) {
      auditLog(
          KIND_REALIZED_READ_UNAVAILABLE,
          subject(
              "reason",
              "auto:account_realized_read_unavailable",
              "broker_target",
              brokerTarget,
              "consecutive_ticks",
              consecutiveRealizedReadFailures,
              "error",
              err,
              "trading_day",
              tradingDay,
              "scope",
              "account"));
      realizedReadUnavailableAlerted = true;
    }
  }

  /** Fetches the live bid for one contract; null when the quote is UNAVAILABLE/FAILED/absent. */
  private BigDecimal liveBid(String contractSymbol) {
    GetOptionQuoteRequest qreq = new GetOptionQuoteRequest();
    qreq.setSchemaVersion(1L);
    qreq.setTenantId(input.getTenantId());
    // The account scope has no single strategy; the quote read is symbol-only, but the request
    // schema requires a strategy_id. Use the account sentinel (the market-data read does not gate
    // on it).
    qreq.setStrategyId(ACCOUNT_SCOPE);
    qreq.setContractSymbol(contractSymbol);
    OptionQuoteResult quote = optionQuote.getOptionQuote(qreq);
    if (quote == null
        || quote.getStatus() != OptionQuoteResult.Status.OK
        || quote.getBid() == null) {
      return null;
    }
    return quote.getBid();
  }

  private static boolean failsClosed(int listed, int failures) {
    boolean exceedsRelative = (long) failures * RELATIVE_FAILURE_THRESHOLD_MULTIPLIER > listed;
    boolean tripsSmallBookFloor = listed <= SMALL_BOOK_MAX_POSITIONS && failures >= 1;
    return exceedsRelative || tripsSmallBookFloor;
  }

  /**
   * Resolves the effective account loss cap (v&gt;=1 only).
   *
   * <p>Precedence: when {@code account_daily_loss_pct} is set ({@code > 0}) the pct cap is
   * preferred over the absolute threshold. If start-of-day equity is known we return {@code pct x
   * sodEquity}; if not, we attempt to capture it ONCE for the day (lazily) and, on success, use it.
   *
   * <p><b>SOD-equity-unavailable degrade — fail SAFE, not fail loud.</b> If the pct cap is
   * configured but start-of-day equity cannot be read (null broker_target, or the equity snapshot
   * fails/returns ≤0), we do NOT trip on an unknown base and do NOT crash the heartbeat. Instead we
   * DEFER the pct evaluation this tick and FALL BACK to the absolute threshold only if one is also
   * configured (else return null => the cap is inert this tick). {@code sodEquity} stays null, so
   * the next heartbeat retries the capture until it succeeds. Rationale: the pct cap is an ADDITIVE
   * portfolio safety net — the per-strategy {@code daily_loss_threshold} and the notional cap still
   * protect — so an equity-read outage must not disable trading via a spurious account trip, nor
   * trip on a guessed base. (Distinct from {@code auto:account_mtm_unavailable}, which fail-CLOSES
   * on an OPEN-POSITION quote outage — a different condition; do not conflate.)
   */
  private BigDecimal resolveEffectiveThreshold(BigDecimal absolute, BigDecimal pct) {
    if (pct == null || pct.signum() <= 0) {
      return absolute; // pct cap not configured => legacy absolute path.
    }
    if (sodEquity == null) {
      // Capture SOD equity ONCE per day, lazily, on the first heartbeat that needs it.
      sodEquity = captureSodEquity();
    }
    if (sodEquity == null || sodEquity.signum() <= 0) {
      // DEFER: equity unknown this tick. Fall back to the absolute threshold if one exists.
      return absolute;
    }
    return pct.multiply(sodEquity);
  }

  /**
   * Reads the tenant's start-of-day account equity by dispatching the read-only {@link
   * AccountSnapshotActivity} (Alpaca {@code /v2/account} net-liquidation {@code equity}) to the
   * tenant's {@code broker-<target>} task queue — the SAME activity the dashboard's {@code
   * AccountSnapshotWorkflow} and the notional-cap sizing path already use (no exec/broker contract
   * change). Returns {@code null} when the broker_target cannot be resolved or the read fails — the
   * caller fails SAFE (defers). Determinism: the request is built from the resolved broker_target +
   * tenant id only (no clock/random reads).
   *
   * <p><b>"Start-of-day" = the equity at the FIRST market-open heartbeat that needs it that day</b>
   * (captured lazily, then frozen for the day and carried across continue-as-new), NOT the prior
   * session's close. On a mid-session pod restart the first post-restart heartbeat re-captures from
   * a now-null {@code sodEquity}, so the "SOD" base can drift to the intra-day equity at restart.
   * Accepted: {@code AccountSnapshotResult} exposes only current {@code equity} (no prior-close /
   * last_equity field), and the pct cap is an additive net where an approximate base is acceptable;
   * a drift on restart loosens/tightens the cap slightly rather than disabling it.
   *
   * <p><b>Degrade, do not throw.</b> The stub build ({@link ExecActivitiesFactory#taskQueueFor})
   * and {@link AccountSnapshotRequest.BrokerTarget#fromValue} are INSIDE the try: a legal-but-
   * unroutable {@code broker_target} (a bare {@code paper}/{@code live} in {@code
   * ExecActivitiesFactory.LEGACY_BARE_TARGETS}) throws {@code ApplicationFailure}/{@code
   * IllegalArgumentException} synchronously, and we want that to DEFER (return null) like every
   * other unavailable-equity path — NOT propagate to {@code run()} as a heartbeat error. This stays
   * inside the existing {@code v>=1} pct path and adds no command, so it needs no new version gate.
   */
  private BigDecimal captureSodEquity() {
    String brokerTarget = tenantConfig.tenantBrokerTarget(input.getTenantId());
    if (brokerTarget == null || brokerTarget.isBlank()) {
      return null;
    }
    try {
      AccountSnapshotActivity accountStub =
          Workflow.newActivityStub(
              AccountSnapshotActivity.class,
              ActivityOptions.newBuilder()
                  .setTaskQueue(ExecActivitiesFactory.taskQueueFor(brokerTarget))
                  .setStartToCloseTimeout(Duration.ofSeconds(15))
                  .setScheduleToCloseTimeout(Duration.ofSeconds(60))
                  .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                  .build());
      AccountSnapshotRequest request = new AccountSnapshotRequest();
      request.setSchemaVersion(1L);
      request.setBrokerTarget(AccountSnapshotRequest.BrokerTarget.fromValue(brokerTarget));
      request.setTenantId(input.getTenantId());
      request.setCorrelationId(input.getTenantId() + "/account/sod-equity");
      AccountSnapshotResult result = accountStub.accountSnapshot(request);
      return result == null ? null : result.getEquity();
    } catch (TemporalFailure | IllegalArgumentException e) {
      // Fail SAFE: a broker/equity outage (after Temporal's own retries) OR an unroutable/bare
      // broker_target leaves sodEquity null so the pct check defers and retries next tick — it does
      // NOT trip on an unknown base and does NOT surface as a heartbeat error / cap-off audit spam.
      Workflow.getLogger(AccountKillSwitchWorkflowImpl.class)
          .warn(
              "SOD-equity snapshot failed; deferring pct cap this tick tenant={} broker_target={} err={}",
              input.getTenantId(),
              brokerTarget,
              e.getMessage());
      return null;
    }
  }

  @Override
  public void tripValidator(TripKillSwitchRequest request) {
    if (tripped) {
      throw new IllegalStateException("already_tripped");
    }
    if (request.getReason() == null || request.getReason().isBlank()) {
      throw new IllegalArgumentException("reason_required");
    }
    if (request.getActor() == null || request.getActor().isBlank()) {
      throw new IllegalArgumentException("actor_required");
    }
  }

  @Override
  public void trip(TripKillSwitchRequest request) {
    doTrip(request.getReason(), request.getActor(), request.getValue());
  }

  @Override
  public void resetValidator(ResetKillSwitchRequest request) {
    if (!tripped) {
      throw new IllegalStateException("not_tripped");
    }
    String a1 = request.getApproverId1();
    String a2 = request.getApproverId2();
    if (a1 == null || a1.isBlank()) {
      throw new IllegalArgumentException("approver_id_1_required");
    }
    if (a2 == null || a2.isBlank()) {
      throw new IllegalArgumentException("approver_id_2_required");
    }
    if (a1.equals(a2)) {
      throw new IllegalArgumentException("approvers_must_differ");
    }
  }

  @Override
  public void reset(ResetKillSwitchRequest request) {
    long cooldownSecs = DEFAULT_RESET_COOLDOWN_SECS;
    this.tripped = false;
    this.reason = "";
    this.actor = "";
    this.trippedAt = null;
    this.coolingDownUntil = workflowNow().plusSeconds(cooldownSecs);

    Map<String, Object> subj =
        subject(
            "approver_id_1",
            request.getApproverId1(),
            "approver_id_2",
            request.getApproverId2(),
            "cooling_down_until",
            coolingDownUntil,
            "cooldown_secs",
            cooldownSecs);
    if (request.getNote() != null && !request.getNote().isBlank()) {
      subj.put("note", request.getNote());
    }
    auditLog(KIND_KILL_SWITCH_RESET_APPROVED, subj);
  }

  @Override
  public KillSwitchState killswitchState() {
    KillSwitchState s = new KillSwitchState();
    s.setSchemaVersion(1L);
    s.setTripped(tripped);
    s.setReason(reason == null ? "" : reason);
    s.setActor(actor == null ? "" : actor);
    s.setTrippedAt(trippedAt);
    s.setCoolingDownUntil(coolingDownUntil);
    s.setTradingDay(tradingDay);
    return s;
  }

  private void doTrip(String tripReason, String tripActor, BigDecimal tripValue) {
    this.tripped = true;
    this.reason = tripReason;
    this.actor = tripActor;
    this.trippedAt = workflowNow();

    Map<String, Object> subj =
        subject(
            "reason", tripReason,
            "actor", tripActor,
            "tripped_at", trippedAt,
            "trading_day", tradingDay,
            "scope", "account");
    if (tripValue != null) {
      subj.put("value", tripValue);
    }
    auditLog(KIND_KILL_SWITCH_TRIPPED, subj);

    String selfWfId = Workflow.getInfo().getWorkflowId();
    // Best-effort async cascade: fired detached so the trip update returns promptly. Known
    // follow-up (tracked, not a hard block): if this workflow continues-as-new before the cascade
    // promise resolves, the detached cascade could be orphaned mid-fan-out. Accepted because the
    // per-position EOD/expiry/time backstops still flatten each leg independently; the cascade only
    // accelerates the flatten, it is not the sole safety net.
    Async.function(
        cascade::cascadeAccountRiskBreach, input.getTenantId(), selfWfId, tripReason, tripActor);
  }

  private void auditLog(String kind, Map<String, Object> subj) {
    audit.log(auditEvent(kind, subj));
  }

  private AuditEvent auditEvent(String kind, Map<String, ?> subj) {
    AuditEvent e = new AuditEvent();
    e.setSchemaVersion(1L);
    e.setTenantId(input.getTenantId());
    e.setStrategyId(ACCOUNT_SCOPE);
    e.setEventId(Workflow.randomUUID().toString());
    e.setOccurredAt(workflowNow());
    e.setKind(kind);
    e.setSubject(new LinkedHashMap<>(subj));
    e.setActor("workflow:AccountKillSwitchWorkflow");
    e.setWorkflowId(Workflow.getInfo().getWorkflowId());
    e.setCorrelationId(input.getTenantId() + "/account");
    return e;
  }

  private static Map<String, Object> subject(Object... kv) {
    if ((kv.length & 1) != 0) {
      throw new IllegalArgumentException("subject() requires an even number of key/value args");
    }
    Map<String, Object> m = new LinkedHashMap<>(kv.length);
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  private static OffsetDateTime workflowNow() {
    return OffsetDateTime.ofInstant(
        Instant.ofEpochMilli(Workflow.currentTimeMillis()), ZoneOffset.UTC);
  }
}
