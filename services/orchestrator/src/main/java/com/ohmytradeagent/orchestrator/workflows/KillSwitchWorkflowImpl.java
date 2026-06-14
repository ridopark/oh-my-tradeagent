package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.KillSwitchState;
import com.ohmytradeagent.contract.KillSwitchWorkflowInput;
import com.ohmytradeagent.contract.LivePromotionApprovalRequest;
import com.ohmytradeagent.contract.ResetKillSwitchRequest;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.TripKillSwitchRequest;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.DailyPnlActivities;
import com.ohmytradeagent.orchestrator.activities.KillSwitchCascadeActivities;
import com.ohmytradeagent.orchestrator.activities.LivePromotionActivities;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import com.ohmytradeagent.orchestrator.activities.StrategyActivities;
import io.temporal.activity.ActivityOptions;
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
 * (trip, reset) are dual-control gated by their Validators; the trip path also fan-outs a {@code
 * riskBreach} signal cascade to every Running workflow under {@code TenantStrategy} via the cascade
 * Activity (executed asynchronously so the Update ACKs fast).
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
   * B2 (P0c-b1) change-id for the live kill-switch heartbeat floor. Gates the fail-closed trip on a
   * {@code -live} strategy that reaches the heartbeat with no valid {@code daily_loss_threshold}.
   * For every pre-B2 in-flight history {@link Workflow#getVersion} returns {@link
   * Workflow#DEFAULT_VERSION} (no marker recorded), so the heartbeat command stream is
   * byte-identical to the legacy path and replay stays deterministic.
   */
  private static final String VERSION_KILLSWITCH_LIVE_FLOOR = "killswitch-live-floor";

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
  private final LivePromotionActivities livePromotion =
      Workflow.newActivityStub(LivePromotionActivities.class, DEFAULT_OPTIONS);

  // State
  private final KillSwitchWorkflowInput input;
  private boolean tripped;
  private String reason = "";
  private String actor = "";
  private OffsetDateTime trippedAt;
  private OffsetDateTime coolingDownUntil;
  private LocalDate tradingDay;

  // Assigning input via @WorkflowInit (runs before any Signal/Update handler) closes a race where
  // a fast caller could submit `trip`/`reset` before the @WorkflowMethod body executed and reach
  // auditEvent() with input == null.
  @WorkflowInit
  public KillSwitchWorkflowImpl(KillSwitchWorkflowInput in) {
    if (in.getSchemaVersion() == null || in.getSchemaVersion() > 2L) {
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
    carry.setSchemaVersion(2L);
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
    return carry;
  }

  /**
   * Heartbeat tick: refreshes the trading day, then — if market is open and not already tripped —
   * pulls strategy config, computes realized PnL, and auto-trips when the loss threshold is
   * crossed.
   */
  private void heartbeat() {
    LocalDate today = calendar.todayEt();
    if (!today.equals(tradingDay)) {
      // Day rollover — reset day-scoped state. tripped/coolingDownUntil persist across days.
      this.tradingDay = today;
    }

    if (tripped) {
      return;
    }
    if (!calendar.isMarketOpen()) {
      return;
    }

    StrategyConfig cfg = strategy.get(input.getTenantId(), input.getStrategyId());
    BigDecimal threshold = cfg.getDailyLossThreshold();
    int v = Workflow.getVersion(VERSION_KILLSWITCH_LIVE_FLOOR, Workflow.DEFAULT_VERSION, 1);
    boolean isLive = isLiveTarget(cfg.getBrokerTarget());
    if (threshold == null || threshold.signum() <= 0) {
      if (v == Workflow.DEFAULT_VERSION || !isLive) {
        // Legacy in-flight replays + all paper/non-live: original opt-out behavior, unchanged.
        return;
      }
      // v>=1 && live && no valid loss gate: an upstream control was bypassed on a real-money
      // strategy — fail closed. Trip with a DISTINCT reason so reporting never conflates this with
      // a
      // real daily-loss trip. doTrip already emits KIND_KILL_SWITCH_TRIPPED audit + the cascade
      // flatten.
      doTrip("auto:missing_loss_threshold", "auto:missing_loss_threshold", null);
      return;
    }
    BigDecimal pnlValue =
        pnl.computeRealizedPnl(input.getTenantId(), input.getStrategyId(), tradingDay);
    if (pnlValue.compareTo(threshold.negate()) <= 0) {
      doTrip("auto:daily_loss", "auto:daily_loss", pnlValue);
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
    long cooldownSecs = resetCooldownSecs();
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
  public void recordLivePromotionValidator(LivePromotionApprovalRequest request) {
    if (request.getTenantId() == null || request.getTenantId().isBlank()) {
      throw new IllegalArgumentException("tenant_id_required");
    }
    if (request.getStrategyId() == null || request.getStrategyId().isBlank()) {
      throw new IllegalArgumentException("strategy_id_required");
    }
    if (request.getBrokerTarget() == null || request.getBrokerTarget().isBlank()) {
      throw new IllegalArgumentException("broker_target_required");
    }
    String a1 = request.getApproverId1();
    String a2 = request.getApproverId2();
    if (a1 == null || a1.isBlank() || a2 == null || a2.isBlank() || a1.equals(a2)) {
      throw new IllegalArgumentException("approvers_must_differ");
    }
  }

  @Override
  public void recordLivePromotion(LivePromotionApprovalRequest request) {
    // Validation + audit emission live in the Activity. The workflow Update is a thin pass-through
    // so the api-gateway can reach the orchestrator over Temporal without depending on the
    // orchestrator service directly. No kill-switch state is mutated; the existing trip/reset
    // paths are untouched.
    livePromotion.approve(request);
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

  /**
   * One notion of "live" — mirrors {@link
   * com.ohmytradeagent.orchestrator.bootstrap.StrategyConfigInvariants}'s {@code -live} predicate
   * exactly: a non-null {@code broker_target} whose value ends with {@code "-live"}. A null
   * broker_target is NOT live (paper / unconfigured strategies keep the original opt-out behavior).
   */
  private static boolean isLiveTarget(StrategyConfig.BrokerTarget t) {
    return t != null && t.value() != null && t.value().endsWith("-live");
  }
}
