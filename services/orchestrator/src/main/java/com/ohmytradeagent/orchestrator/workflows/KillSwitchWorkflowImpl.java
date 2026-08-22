package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.KillSwitchState;
import com.ohmytradeagent.contract.KillSwitchWorkflowInput;
import com.ohmytradeagent.contract.ResetKillSwitchRequest;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.TripKillSwitchRequest;
import com.ohmytradeagent.contract.activities.DailyPnlExecActivity;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.DailyPnlActivities;
import com.ohmytradeagent.orchestrator.activities.KillSwitchCascadeActivities;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import com.ohmytradeagent.orchestrator.activities.StrategyActivities;
import com.ohmytradeagent.orchestrator.bootstrap.StrategyConfigInvariants;
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
import java.util.Map;

/**
 * Phase 5 kill switch impl. Lives on the {@code orchestrator-core} task queue. Heartbeats every 60s
 * during market hours; auto-trips when realized PnL crosses {@code -daily_loss_threshold}. Updates
 * (trip, reset) are guarded by their Validators; the trip path also fan-outs a {@code riskBreach}
 * signal cascade to every Running workflow under {@code TenantStrategy} via the cascade Activity
 * (executed asynchronously so the Update ACKs fast).
 *
 * <p>Determinism: all time comes from {@link Workflow#currentTimeMillis()}; all randomness from
 * {@link Workflow#randomUUID()}; cross-workflow visibility queries run inside an Activity (never in
 * workflow code).
 */
public class KillSwitchWorkflowImpl implements KillSwitchWorkflow {

  // Audit kinds
  private static final String KIND_KILL_SWITCH_TRIPPED = "KillSwitchTripped";
  private static final String KIND_KILL_SWITCH_RESET_APPROVED = "KillSwitchResetApproved";
  private static final String KIND_KILL_SWITCH_HEARTBEAT_ERROR = "KillSwitchHeartbeatError";

  /**
   * PLAN-2026-08-12: emitted when the trading-day rollover clears a day-scoped {@code
   * auto:daily_loss} trip, so the un-halt is visible in {@code audit_log} rather than a silent
   * state flip. Carries the prior reason/actor/tripped_at and both trading days. Deliberately NOT
   * matched by {@code KillSwitchAlerter} (which pages only on {@code KillSwitchTripped}) — this is
   * a record, not a page.
   */
  private static final String KIND_KILL_SWITCH_CLEARED_ON_ROLLOVER = "KillSwitchClearedOnRollover";

  /**
   * Issue #669: the once-per-trading-day "this switch is STILL tripped" page. A tripped switch
   * holding no positions previously emitted nothing, forever — staging_paper sat halted NINE DAYS
   * (2026-08-03→12) indistinguishable from healthy idle. Actor-agnostic: every operator halt and
   * sticky auto trip pages each morning until cleared or reset.
   */
  private static final String KIND_KILL_SWITCH_STILL_TRIPPED = "KillSwitchStillTripped";

  /**
   * Issue #668: an operator trip landed on a switch already auto-tripped and took OWNERSHIP of it
   * (actor/reason overwritten, no second cascade). Without this, a one-click Deactivate during an
   * auto:daily_loss day reported success while leaving actor=auto:daily_loss — and #667's rollover
   * then silently re-armed a strategy the operator had deliberately shut off.
   */
  private static final String KIND_KILL_SWITCH_TRIP_REATTRIBUTED = "KillSwitchTripReattributed";

  /**
   * The actor (and reason) stamped by the auto daily-loss heartbeat trip — the ONLY day-scoped trip
   * this workflow takes, and therefore the only one the rollover clears. Every other actor ({@code
   * operator:*} from {@code KillSwitchController} / {@code LiveActivationGateActivitiesImpl},
   * {@code auto:missing_loss_threshold} from the config fail-closed path) survives the rollover.
   *
   * <p>The match is an EXACT equality on {@code actor}, never a {@code startsWith("auto:")} prefix
   * (that would sweep in the config fail-closed trip) and never on {@code reason} ({@code reason}
   * is free text taken straight from the trip request body, so it is caller-shaped; {@code actor}
   * is either set by this workflow or {@code operator:}-prefixed by the only two callers).
   */
  private static final String TRIP_ACTOR_DAILY_LOSS = "auto:daily_loss";

  /**
   * B2 (P0c-b1) change-id for the live kill-switch heartbeat floor. Gates the fail-closed trip on a
   * {@code -live} strategy that reaches the heartbeat with no valid {@code daily_loss_threshold}.
   * For every pre-B2 in-flight history {@link Workflow#getVersion} returns {@link
   * Workflow#DEFAULT_VERSION} (no marker recorded), so the heartbeat command stream is
   * byte-identical to the legacy path and replay stays deterministic.
   */
  private static final String VERSION_KILLSWITCH_LIVE_FLOOR = "killswitch-live-floor";

  /**
   * Phase 2 (PLAN-2026-06-30) change-id for re-sourcing the realized-P&amp;L input from the exec
   * {@code order_intent_journal} (broker truth) instead of {@code audit_log}. At {@code v>=1} the
   * heartbeat calls the broker_target-routed {@link DailyPnlExecActivity}; at {@link
   * Workflow#DEFAULT_VERSION} it calls the legacy {@link DailyPnlActivities#computeRealizedPnl}
   * ({@code audit_log}) — byte-identical to the legacy replay path. Read ONCE early in {@link
   * #heartbeat()} (mirrors {@link #VERSION_KILLSWITCH_LIVE_FLOOR}). Independent history per
   * workflow — the same string is reused by {@code AccountKillSwitchWorkflowImpl}.
   */
  static final String VERSION_KILLSWITCH_REALIZED_FROM_EXEC =
      "killswitch-realized-from-exec-journal-v1";

  /**
   * Phase 3 (PLAN-2026-07-15 single-account-loss-rule) change-id: makes a null/≤0 per-strategy
   * {@code daily_loss_threshold} on a {@code -live} strategy a paper-like NO-OP instead of the
   * fail-closed {@code auto:missing_loss_threshold} trip. The account-level cap is now the sole
   * daily-loss breaker and the boot invariant ({@code LiveRequiredGateValidator}) guarantees it is
   * armed for every {@code -live} tenant (and {@code TenantConfigWriter} is tighten-only so it can
   * never be removed), so the per-strategy heartbeat can stop tripping on a missing threshold
   * without a new activity read. At {@link Workflow#DEFAULT_VERSION} (every pre-Phase-3 in-flight
   * history — no marker recorded) the EXACT legacy trip is preserved so the heartbeat command
   * stream replays byte-identically. Read ONCE early in {@link #heartbeat()} after the two existing
   * gates (stable scope; never reorders their recorded markers).
   */
  static final String VERSION_KILLSWITCH_MISSING_THRESHOLD_OPTIONAL =
      "killswitch-missing-threshold-optional-when-account-cap-v1";

  /**
   * PLAN-2026-08-12 change-id: clear a day-scoped {@code auto:daily_loss} trip at the trading-day
   * rollover, so a DAILY loss breaker is actually daily (pre-change, one bad day halted the tenant
   * on day N, N+1, N+2 … until a human reset it — prod-kipark refused every BTO at the 2026-08-12
   * open off an 2026-08-11 trip; staging_paper had silently taken no entry for nine days).
   *
   * <p><b>Read at the VERY TOP of {@link #heartbeat()}, ABOVE the other three gates — do NOT "tidy"
   * it down beside them.</b> The three existing gates ({@link #VERSION_KILLSWITCH_LIVE_FLOOR},
   * {@link #VERSION_KILLSWITCH_REALIZED_FROM_EXEC}, {@link
   * #VERSION_KILLSWITCH_MISSING_THRESHOLD_OPTIONAL}) are read BELOW the {@code if (tripped)
   * return;} early-return, which short-circuits every tripped tick — and a tripped tick is
   * precisely when this gate has to be readable. Moving this read down beside them makes the
   * feature unreachable for the entire population it exists for. Moving it down LATER, once
   * executions have recorded the marker at the top, is also a needless replay risk. It stays first.
   *
   * <p>Reading a NEW change-id above the existing three does NOT reorder their recorded markers:
   * the SDK resolves version markers by changeId ({@code WorkflowStateMachines.versions} is a
   * {@code Map<String, VersionStateMachine>}), preloads the current workflow task's markers before
   * running workflow code, and for a changeId absent from the recorded history yields the workflow
   * thread until the task's events are exhausted and then returns {@link Workflow#DEFAULT_VERSION}
   * — emitting NO command. So every pre-change in-flight history replays byte-identically.
   *
   * <p><b>The gate covers the STATE MUTATION as well as the audit</b>, and the mutation is the
   * load-bearing half: clearing {@code tripped} at {@link Workflow#DEFAULT_VERSION} would drop the
   * replaying tick out of the {@code if (tripped) return;} short-circuit and into {@code
   * isMarketOpen}/{@code strategy.get}/the realized read — a whole command stream the recorded
   * history does not contain. Pinned by {@code
   * KillSwitchWorkflowImplLegacyReplayTest#legacyTrippedDailyLossRolloverHistoryDoesNotClear}.
   */
  /**
   * Issue #746: honour the post-reset cooldown that {@link #doReset} arms.
   *
   * <p>{@code coolingDownUntil} was written, carried across continue-as-new, set by both reset
   * paths, and projected into {@code killswitch_state} — and NEVER READ in the trip path. So a
   * reset over a still-breaching book re-tripped on the very next heartbeat. Observed live on
   * prod-kipark 2026-08-19: reset at 14:01:48 advertising {@code cooling_down_until=14:02:48.681},
   * re-tripped at {@code 14:02:41.563} — SEVEN SECONDS INSIDE its own window. The cooldown was not
   * short, it was inert, and the value in the query and the reset audit advertised protection that
   * did not exist.
   *
   * <p>The sibling {@code AccountKillSwitchWorkflowImpl} has always honoured its own (":830"). This
   * brings the per-strategy switch into line.
   *
   * <p>MUST be gated: suppressing a trip removes a {@code doTrip} — its {@code KillSwitchTripped}
   * audit Log activity AND its cascade — from the command stream. That is a command-COUNT
   * divergence for in-flight executions, not a payload difference.
   */
  static final String VERSION_KILLSWITCH_HONOUR_RESET_COOLDOWN =
      "killswitch-honour-reset-cooldown-v1";

  static final String VERSION_KILLSWITCH_CLEAR_DAILY_LOSS_ON_ROLLOVER =
      "killswitch-clear-daily-loss-trip-on-rollover-v1";

  /**
   * Issue #669: gate for the once-per-day still-tripped page. Read right after the rollover-clear
   * gate (same stable pre-tripped-return scope; appending after it keeps every recorded marker
   * order). At {@code DEFAULT_VERSION} a tripped tick stays the byte-identical bare early-return;
   * at {@code v>=1} the first market-open tick of a new trading day while tripped emits {@link
   * #KIND_KILL_SWITCH_STILL_TRIPPED} (one isMarketOpen activity + one audit command, both strictly
   * behind the gate).
   */
  static final String VERSION_KILLSWITCH_STILL_TRIPPED_PAGE = "killswitch-still-tripped-page-v1";

  /**
   * Consecutive exec-realized-read failures the heartbeat tolerates before paging (guardrail G1). A
   * failed read is NEVER treated as a loss (never a spurious trip); the counter + bounded alert
   * make a persistent exec outage visible instead of silently skipping ticks forever. 3 ticks at
   * the 60s cadence = ~3 min — past a single transient blip (absorbed by the stub's own retry
   * budget) but fast enough to surface "the daily-loss cap is not reading P&amp;L". Package-private
   * for test override.
   */
  static int REALIZED_READ_FAILURE_ALERT_TICKS = 3;

  /** Audit kind emitted when the exec-realized read has been unavailable for too many ticks. */
  private static final String KIND_REALIZED_READ_UNAVAILABLE = "KillSwitchRealizedReadUnavailable";

  /** Heartbeat cadence — 60s per PLAN.md kill-switch flow. */
  static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(60);

  /** Fallback cooldown when the strategy config does not set {@code reset_cooldown_secs}. */
  static final long DEFAULT_RESET_COOLDOWN_SECS = 60L;

  /**
   * History-event watermark for {@link Workflow#continueAsNew(Object...)}. Set at ~5x safety margin
   * below the Temporal frontend default ~51,200 cap and ~10x above the per-trading-day event count
   * the heartbeat loop emits, so the workflow continues-as-new approximately daily and never
   * mid-bar. Carries forward {@code tripped}, {@code reason}, {@code actor}, {@code trippedAt},
   * {@code coolingDownUntil}, and {@code tradingDay} via the v2 {@link KillSwitchWorkflowInput}
   * fields so state survives the run-id rotation. Issue #124.
   *
   * <p>Package-private and non-final so tests can lower the threshold via reflection — keeps the
   * production code from gaining a configuration knob that has no operational use (KISS).
   */
  static long historyLengthWatermark = 10_000L;

  private static final ActivityOptions DEFAULT_OPTIONS =
      ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build();
  private static final ActivityOptions CASCADE_OPTIONS =
      ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build();

  private final AuditActivities audit =
      Workflow.newActivityStub(AuditActivities.class, DEFAULT_OPTIONS);
  private final MarketCalendarActivities calendar =
      Workflow.newActivityStub(MarketCalendarActivities.class, DEFAULT_OPTIONS);
  private final StrategyActivities strategy =
      Workflow.newActivityStub(StrategyActivities.class, DEFAULT_OPTIONS);
  private final DailyPnlActivities pnl =
      Workflow.newActivityStub(DailyPnlActivities.class, DEFAULT_OPTIONS);
  private final KillSwitchCascadeActivities cascade =
      Workflow.newActivityStub(KillSwitchCascadeActivities.class, CASCADE_OPTIONS);

  // State
  private final KillSwitchWorkflowInput input;
  private boolean tripped;
  private String reason = "";
  private String actor = "";
  private OffsetDateTime trippedAt;
  private OffsetDateTime coolingDownUntil;
  private LocalDate tradingDay;

  /**
   * Issue #669: the trading day the still-tripped page last fired (or was quiet-stamped on the trip
   * day), bounding the page to once per day. CARRIED across continue-as-new — review-caught: an
   * uncarried field resets to null, the tripped branch reads null as "trip day" and quiet-stamps,
   * and the roll day would LOSE its page entirely (never duplicate it).
   */
  private LocalDate lastStillTrippedPageDay;

  /**
   * Guardrail G1: consecutive exec-realized-read failures on the {@code v>=1} path (deterministic
   * workflow state, no commands). A failed read defers the trip this tick (never a spurious trip)
   * and increments this counter; on crossing {@link #REALIZED_READ_FAILURE_ALERT_TICKS} the
   * heartbeat emits ONE bounded alert (then keeps the alerted flag so it does not page every tick).
   * A successful read (or a trip) clears both. Not carried across continue-as-new — an in-flight
   * outage re-accumulates from zero after a ~daily CAN (observability-only, mild under-paging).
   */
  private int consecutiveRealizedReadFailures;

  private boolean realizedReadUnavailableAlerted;

  // Assigning input via @WorkflowInit (runs before any Signal/Update handler) closes a race where
  // a fast caller could submit `trip`/`reset` before the @WorkflowMethod body executed and reach
  // auditEvent() with input == null.
  @WorkflowInit
  public KillSwitchWorkflowImpl(KillSwitchWorkflowInput in) {
    if (in.getSchemaVersion() == null || in.getSchemaVersion() > 3L) {
      throw new IllegalArgumentException(
          "KillSwitchWorkflowInput schema_version unsupported: " + in.getSchemaVersion());
    }
    this.input = in;
    // Hydrate carry-forward state from a prior run's continueAsNew. Fresh bootstrap inputs
    // (KillSwitchBootstrapper) leave every optional field null, so we keep the existing defaults.
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
    if (in.getLastStillTrippedPageDay() != null) {
      this.lastStillTrippedPageDay = in.getLastStillTrippedPageDay();
    }
  }

  @Override
  public String run(KillSwitchWorkflowInput in) {
    // `in` is consumed by the @WorkflowInit constructor; the parameter only stays because
    // the @WorkflowMethod signature must match KillSwitchWorkflow.run.
    if (this.tradingDay == null) {
      // Fresh bootstrap. Carry-forward runs already have tradingDay hydrated from `in`.
      this.tradingDay = calendar.todayEt();
    }

    while (true) {
      Workflow.sleep(HEARTBEAT_INTERVAL);
      try {
        heartbeat();
      } catch (RuntimeException e) {
        // Best-effort: keep the workflow alive on transient errors so manual trip/reset still work.
        auditLog(
            KIND_KILL_SWITCH_HEARTBEAT_ERROR,
            subject("error", e.getMessage(), "trading_day", tradingDay));
      }
      // Drain-and-continueAsNew once history crosses the watermark. Placed *after* heartbeat() so
      // the just-scheduled activity completion is the last event in the old history, and *outside*
      // the try/catch above so the DestroyWorkflowThreadError that continueAsNew throws propagates
      // to Temporal as it expects. Issue #124.
      if (Workflow.getInfo().getHistoryLength() > historyLengthWatermark) {
        Workflow.continueAsNew(buildCarryForwardInput());
        // continueAsNew throws DestroyWorkflowThreadError — anything below this line is
        // unreachable.
      }
    }
  }

  /**
   * Builds a v2 {@link KillSwitchWorkflowInput} populated with current state for {@link
   * Workflow#continueAsNew(Object...)}. Tenant/strategy ids carry over from {@link #input}; the
   * carry-forward fields snapshot the workflow's mutable state at the boundary. The next run's
   * {@code @WorkflowInit} hydrates them back into the same field names.
   */
  private KillSwitchWorkflowInput buildCarryForwardInput() {
    KillSwitchWorkflowInput carry = new KillSwitchWorkflowInput();
    // #669: v3 ONLY when the page-day is carried, so a never-tripped carry stays the v2 shape an
    // old pod mid-rollout accepts (same discipline as AccountKillSwitchWorkflowImpl's builder).
    carry.setSchemaVersion(lastStillTrippedPageDay != null ? 3L : 2L);
    carry.setTenantId(input.getTenantId());
    carry.setStrategyId(input.getStrategyId());
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
    // #669 review fix: carry the page day — without it the roll day LOSES its page (the null
    // quiet-stamp reads a fresh run as "trip day"), the exact inverse of the duplicate the
    // original javadoc guessed at.
    carry.setLastStillTrippedPageDay(lastStillTrippedPageDay);
    return carry;
  }

  /**
   * Heartbeat tick: refreshes the trading day, then — if market is open and not already tripped —
   * pulls strategy config, computes realized PnL, and auto-trips when the loss threshold is
   * crossed.
   */
  private void heartbeat() {
    // PLAN-2026-08-12: read the rollover-clear gate FIRST, unconditionally, at a stable scope. It
    // MUST be read before the `if (tripped) return;` below — that early-return short-circuits the
    // whole tick (and every other gate), and a tripped tick is exactly when this one matters. See
    // VERSION_KILLSWITCH_CLEAR_DAILY_LOSS_ON_ROLLOVER's javadoc for why reading a new change-id
    // above the three existing gates cannot reorder their recorded markers, and why this read must
    // not be moved down beside them.
    int clearDailyLossOnRollover =
        Workflow.getVersion(
            VERSION_KILLSWITCH_CLEAR_DAILY_LOSS_ON_ROLLOVER, Workflow.DEFAULT_VERSION, 1);
    // #669: read right after the rollover-clear gate (appended — recorded marker order preserved).
    int stillTrippedPageVersion =
        Workflow.getVersion(VERSION_KILLSWITCH_STILL_TRIPPED_PAGE, Workflow.DEFAULT_VERSION, 1);

    LocalDate today = calendar.todayEt();
    if (!today.equals(tradingDay)) {
      // Day rollover — reset day-scoped state. coolingDownUntil persists across days: it is a
      // POST-RESET debounce, not day-scoped state, and it is already in the past by any rollover.
      //
      // v>=1: a DAILY loss breaker must be daily. Clear ONLY the day-scoped auto:daily_loss trip;
      // every operator halt and config fail-closed trip persists (fail-closed on any unrecognised
      // actor). Sits inside the rollover branch and BEFORE the tripped early-return below, so a
      // cleared switch falls through to NORMAL evaluation on this same tick. At DEFAULT_VERSION
      // none of it runs — see the change-id javadoc for why the gate covers the mutation too.
      if (clearDailyLossOnRollover >= 1 && tripped && TRIP_ACTOR_DAILY_LOSS.equals(actor)) {
        // Snapshot the subject BEFORE the wipe (the fields it records are the ones being cleared).
        Map<String, Object> subj =
            subject(
                "reason", reason,
                "actor", actor,
                "tripped_at", trippedAt,
                "prior_trading_day", tradingDay,
                "trading_day", today);
        clearTrippedState();
        auditLog(KIND_KILL_SWITCH_CLEARED_ON_ROLLOVER, subj);
      }
      this.tradingDay = today;
    }

    if (tripped) {
      // #669: a tripped-and-FLAT switch previously said nothing, forever (staging_paper: nine
      // silent days). Once per trading day, on the first market-open tick, page that the switch is
      // STILL tripped — actor-agnostic, so operator halts and sticky auto trips all stay visible.
      // The rollover-clear above runs first, so a switch it cleared never reaches this. Both new
      // commands (isMarketOpen + the audit) are strictly behind the v>=1 gate; a tripped tick at
      // DEFAULT_VERSION is the byte-identical bare return.
      if (stillTrippedPageVersion >= 1 && lastStillTrippedPageDay == null) {
        // Trip day (or a fresh run adopted already-tripped): the trip itself paged — the daily
        // reminder starts on the NEXT day. Quiet stamp, no page. (doTrip also stamps, but a trip
        // landing before the first heartbeat sees tradingDay still null.)
        lastStillTrippedPageDay = today;
      } else if (stillTrippedPageVersion >= 1
          && !today.equals(lastStillTrippedPageDay)
          && calendar.isMarketOpen()) {
        lastStillTrippedPageDay = today;
        auditLog(
            KIND_KILL_SWITCH_STILL_TRIPPED,
            subject(
                "reason", reason,
                "actor", actor,
                "tripped_at", trippedAt,
                "trading_day", today));
      }
      return;
    }
    // #746: honour the cooldown doReset armed. Read AFTER the tripped early-return deliberately —
    // the window only means anything on an UNTRIPPED switch, and a tripped tick has already
    // short-circuited. Placed BEFORE the market-open check to mirror AccountKillSwitchWorkflowImpl,
    // so the two switches agree on evaluation order.
    if (Workflow.getVersion(VERSION_KILLSWITCH_HONOUR_RESET_COOLDOWN, Workflow.DEFAULT_VERSION, 1)
            >= 1
        && coolingDownUntil != null
        && workflowNow().isBefore(coolingDownUntil)) {
      // Intentional inert window: the operator reset knowing the book was still down, and this is
      // the breathing room that reset was supposed to buy. Not a defer, not an error — say nothing.
      return;
    }
    if (!calendar.isMarketOpen()) {
      return;
    }

    StrategyConfig cfg = strategy.get(input.getTenantId(), input.getStrategyId());
    BigDecimal threshold = cfg.getDailyLossThreshold();
    int v = Workflow.getVersion(VERSION_KILLSWITCH_LIVE_FLOOR, Workflow.DEFAULT_VERSION, 1);
    // Phase 2: read the realized-source gate ONCE, early, at a stable scope (mirrors the live-floor
    // gate above). v>=1 => broker-truth exec journal; DEFAULT_VERSION => legacy audit_log path.
    int realizedVersion =
        Workflow.getVersion(VERSION_KILLSWITCH_REALIZED_FROM_EXEC, Workflow.DEFAULT_VERSION, 1);
    // Phase 3: read the missing-threshold-optional gate ONCE, AFTER the two existing gates so their
    // recorded markers are never reordered on replay. At v>=1 a null/≤0 threshold on a -live
    // strategy is a no-op (the account cap is the sole breaker, guaranteed armed by the boot
    // invariant); at DEFAULT_VERSION the exact legacy trip is preserved (byte-identical replay).
    int missingThresholdOptional =
        Workflow.getVersion(
            VERSION_KILLSWITCH_MISSING_THRESHOLD_OPTIONAL, Workflow.DEFAULT_VERSION, 1);
    boolean isLive = StrategyConfigInvariants.isLive(cfg);
    if (threshold == null || threshold.signum() <= 0) {
      if (v == Workflow.DEFAULT_VERSION || !isLive) {
        // Legacy in-flight replays + all paper/non-live: original opt-out behavior, unchanged.
        return;
      }
      if (missingThresholdOptional >= 1) {
        // Phase 3 (single-account-loss-rule): the account-level cap is now the sole daily-loss
        // breaker and the boot invariant guarantees it armed for every -live tenant, so a null
        // per-strategy daily_loss_threshold no longer trips — it becomes a paper-like no-op.
        return;
      }
      // DEFAULT_VERSION of the Phase-3 gate: preserve the EXACT legacy fail-closed trip so
      // in-flight
      // histories replay byte-identically. v>=1 && live && no valid loss gate: an upstream control
      // was bypassed on a real-money strategy — fail closed. Trip with a DISTINCT reason so
      // reporting never conflates this with a real daily-loss trip. doTrip already emits the
      // KIND_KILL_SWITCH_TRIPPED audit + cascade flatten.
      doTrip("auto:missing_loss_threshold", "auto:missing_loss_threshold", null);
      return;
    }
    BigDecimal pnlValue;
    if (realizedVersion >= 1) {
      // Broker truth: route the realized read to the strategy's broker-<target> exec queue. A
      // failed read (exec pod down / queue backpressure) is a MISSING number, NOT a loss — defer
      // the trip this tick (guardrail G1) rather than trip spuriously. The stub's own bounded
      // retry (below) absorbs a transient blip inside the heartbeat window; a persistent outage
      // trips the consecutive-failure counter and pages a bounded alert.
      String brokerTarget = cfg.getBrokerTarget() == null ? null : cfg.getBrokerTarget().value();
      try {
        pnlValue =
            execRealized(brokerTarget)
                .computeRealizedPnl(input.getTenantId(), input.getStrategyId(), tradingDay);
      } catch (TemporalFailure e) {
        recordRealizedReadFailure(brokerTarget, e.getMessage());
        return; // defer — never trip on a missing P&L number.
      }
      // A good read clears the outage state (and the recovery is silent — no re-arm audit).
      consecutiveRealizedReadFailures = 0;
      realizedReadUnavailableAlerted = false;
    } else {
      // DEFAULT_VERSION: legacy audit_log path — byte-identical to the pre-Phase-2 replay stream.
      pnlValue = pnl.computeRealizedPnl(input.getTenantId(), input.getStrategyId(), tradingDay);
    }
    if (pnlValue.compareTo(threshold.negate()) <= 0) {
      // reason == actor here (the heartbeat is its own actor); TRIP_ACTOR_DAILY_LOSS is the value
      // the rollover clear matches on, so the trip and the clear can never drift apart.
      doTrip(TRIP_ACTOR_DAILY_LOSS, TRIP_ACTOR_DAILY_LOSS, pnlValue);
    }
  }

  /**
   * Builds the broker_target-routed {@link DailyPnlExecActivity} stub in WORKFLOW code (the routing
   * decision must live here — a Spring activity bean has no Workflow context). {@code
   * taskQueueFor(brokerTarget)} is deterministic/replay-safe. Guardrail G1 activity options: a 12s
   * start-to-close (shorter than the 60s heartbeat cadence) with up to 3 bounded retries under a
   * ~40s schedule-to-close ceiling, so a transient exec blip is absorbed by retry within the tick
   * rather than skipping it; a genuine outage exhausts the budget and surfaces as a {@link
   * TemporalFailure} the caller turns into a deferred tick + bounded alert (never a trip).
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
   * Guardrail G1 bookkeeping for a deferred (failed) exec-realized read: increments the consecutive
   * counter and, on crossing {@link #REALIZED_READ_FAILURE_ALERT_TICKS}, emits ONE bounded alert
   * with a DISTINCT reason (mirrors the {@code auto:missing_loss_threshold} precedent shape). Never
   * calls {@code doTrip} — a missing number is not a loss.
   */
  private void recordRealizedReadFailure(String brokerTarget, String err) {
    consecutiveRealizedReadFailures++;
    if (!realizedReadUnavailableAlerted
        && consecutiveRealizedReadFailures >= REALIZED_READ_FAILURE_ALERT_TICKS) {
      auditLog(
          KIND_REALIZED_READ_UNAVAILABLE,
          subject(
              "reason",
              "auto:realized_read_unavailable",
              "broker_target",
              brokerTarget,
              "consecutive_ticks",
              consecutiveRealizedReadFailures,
              "error",
              err,
              "trading_day",
              tradingDay));
      realizedReadUnavailableAlerted = true;
    }
  }

  @Override
  public void tripValidator(TripKillSwitchRequest request) {
    if (request.getReason() == null || request.getReason().isBlank()) {
      throw new IllegalArgumentException("reason_required");
    }
    if (request.getActor() == null || request.getActor().isBlank()) {
      throw new IllegalArgumentException("actor_required");
    }
    if (tripped) {
      // #668: an OPERATOR trip landing on an AUTO-tripped switch is a takeover, not a duplicate.
      // Rejecting it left actor=auto:daily_loss after a one-click Deactivate "succeeded", and
      // #667's rollover then re-armed a strategy the operator had deliberately shut off (the two
      // events are correlated: "lost money today" and "operator shuts it off"). Accept exactly
      // that case; every other repeat trip stays the idempotent rejection. Validator changes are
      // replay-safe by construction — a rejected update is never recorded, and a newly-ACCEPTED
      // takeover only ever appears in new history.
      boolean takeover = isAutoActor(actor) && !isAutoActor(request.getActor());
      if (!takeover) {
        throw new IllegalStateException("already_tripped");
      }
    }
  }

  private static boolean isAutoActor(String a) {
    return a != null && a.startsWith("auto:");
  }

  @Override
  public void trip(TripKillSwitchRequest request) {
    if (tripped) {
      // #668 takeover (validator admitted it): make the operator's intent durable — overwrite
      // actor/reason so the rollover-clear can never re-arm this — WITHOUT a second cascade (the
      // original trip already flattened; a deactivation takeover must not fire another market
      // flatten) and keeping the original trippedAt (the halt began then; only ownership changes).
      // The audit is a new command, but only a newly-accepted takeover reaches here — no recorded
      // history contains one, so no version gate is needed (same argument as arm_trail's).
      Map<String, Object> subj =
          subject(
              "prior_actor", actor,
              "prior_reason", reason,
              "actor", request.getActor(),
              "reason", request.getReason(),
              "tripped_at", trippedAt,
              "trading_day", tradingDay);
      this.actor = request.getActor();
      this.reason = request.getReason();
      auditLog(KIND_KILL_SWITCH_TRIP_REATTRIBUTED, subj);
      return;
    }
    doTrip(request.getReason(), request.getActor(), request.getValue());
  }

  @Override
  public void resetValidator(ResetKillSwitchRequest request) {
    if (!tripped) {
      throw new IllegalStateException("not_tripped");
    }
    String a1 = request.getApproverId1();
    if (a1 == null || a1.isBlank()) {
      throw new IllegalArgumentException("approver_id_1_required");
    }
  }

  @Override
  public void reset(ResetKillSwitchRequest request) {
    long cooldownSecs = resetCooldownSecs();
    OffsetDateTime coolingUntil = workflowNow().plusSeconds(cooldownSecs);

    Map<String, Object> subj =
        subject(
            "approver_id_1",
            request.getApproverId1(),
            "via",
            "manual_reset",
            "cooling_down_until",
            coolingUntil,
            "cooldown_secs",
            cooldownSecs);
    if (request.getNote() != null && !request.getNote().isBlank()) {
      subj.put("note", request.getNote());
    }
    doReset(coolingUntil, subj);
  }

  @Override
  public void resetOnActivationValidator(ResetKillSwitchRequest request) {
    if (!tripped) {
      throw new IllegalStateException("not_tripped");
    }
    String operator = request.getApproverId1();
    if (operator == null || operator.isBlank()) {
      throw new IllegalArgumentException("operator_required");
    }
  }

  @Override
  public void resetOnActivation(ResetKillSwitchRequest request) {
    long cooldownSecs = resetCooldownSecs();
    OffsetDateTime coolingUntil = workflowNow().plusSeconds(cooldownSecs);

    // HONEST audit: a single-operator reset via the one-click live-activation path, marked
    // via=live_activation to distinguish it from a manual reset (via=manual_reset).
    Map<String, Object> subj =
        subject(
            "via",
            "live_activation",
            "operator",
            request.getApproverId1(),
            "cooling_down_until",
            coolingUntil,
            "cooldown_secs",
            cooldownSecs);
    if (request.getNote() != null && !request.getNote().isBlank()) {
      subj.put("note", request.getNote());
    }
    doReset(coolingUntil, subj);
  }

  /**
   * Shared kill-switch reset mutation for both single-operator reset paths — the manual {@link
   * #reset(ResetKillSwitchRequest)} ({@code via=manual_reset}) and the one-click {@link
   * #resetOnActivation(ResetKillSwitchRequest)} ({@code via=live_activation}). Each caller builds
   * its OWN audit subject; this method only clears the tripped state, arms the cooldown, and emits
   * the {@code KillSwitchResetApproved} audit with the caller-provided subject.
   */
  private void doReset(OffsetDateTime coolingUntil, Map<String, Object> subj) {
    clearTrippedState();
    this.coolingDownUntil = coolingUntil;
    auditLog(KIND_KILL_SWITCH_RESET_APPROVED, subj);
  }

  /**
   * Clears the trip tuple as a UNIT — the four fields that {@link #doTrip} sets together and that
   * both {@link #killswitchState()} and {@link #buildCarryForwardInput()} project together. Shared
   * by the two un-trip paths ({@link #doReset} and the trading-day rollover clear in {@link
   * #heartbeat()}) so a field added to the trip state can never be cleared by one and leaked by the
   * other — {@code actor} in particular is the rollover clear's own discriminator, so a stale value
   * there would be self-corrupting rather than cosmetic.
   *
   * <p>Deliberately does NOT touch {@code coolingDownUntil}: only {@link #doReset} arms a cooldown.
   * A rollover clear that armed one would have {@code RiskActivitiesImpl.checkKillSwitch} reject
   * entries with {@code KILL_SWITCH_COOLING_DOWN} for the window it exists to end.
   */
  private void clearTrippedState() {
    this.tripped = false;
    this.reason = "";
    this.actor = "";
    this.trippedAt = null;
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

  /**
   * Core trip mutation. Sets state synchronously so the next query reads {@code tripped=true}
   * immediately, then dispatches the cascade Activity asynchronously so the Update ACKs fast.
   */
  private void doTrip(String tripReason, String tripActor, BigDecimal tripValue) {
    this.tripped = true;
    this.reason = tripReason;
    this.actor = tripActor;
    this.trippedAt = workflowNow();
    // #669: the trip itself pages loudly today — the daily still-tripped reminder starts TOMORROW.
    this.lastStillTrippedPageDay = tradingDay;

    Map<String, Object> subj =
        subject(
            "reason", tripReason,
            "actor", tripActor,
            "tripped_at", trippedAt,
            "trading_day", tradingDay);
    if (tripValue != null) {
      subj.put("value", tripValue);
    }
    auditLog(KIND_KILL_SWITCH_TRIPPED, subj);

    // Fan-out asynchronously: the Activity does listWorkflowExecutions + signal per match.
    String selfWfId = Workflow.getInfo().getWorkflowId();
    Async.function(
        cascade::cascadeRiskBreach,
        input.getTenantId(),
        input.getStrategyId(),
        selfWfId,
        tripReason,
        tripActor);
  }

  private long resetCooldownSecs() {
    try {
      StrategyConfig cfg = strategy.get(input.getTenantId(), input.getStrategyId());
      Long cd = cfg.getResetCooldownSecs();
      return cd != null && cd > 0 ? cd : DEFAULT_RESET_COOLDOWN_SECS;
    } catch (RuntimeException e) {
      return DEFAULT_RESET_COOLDOWN_SECS;
    }
  }

  private void auditLog(String kind, Map<String, Object> subj) {
    audit.log(auditEvent(kind, subj));
  }

  private AuditEvent auditEvent(String kind, Map<String, ?> subj) {
    AuditEvent e = new AuditEvent();
    e.setSchemaVersion(1L);
    e.setTenantId(input.getTenantId());
    e.setStrategyId(input.getStrategyId());
    e.setEventId(Workflow.randomUUID().toString());
    e.setOccurredAt(workflowNow());
    e.setKind(kind);
    e.setSubject(new LinkedHashMap<>(subj));
    e.setActor("workflow:KillSwitchWorkflow");
    e.setWorkflowId(Workflow.getInfo().getWorkflowId());
    e.setCorrelationId(input.getTenantId() + "/" + input.getStrategyId());
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
