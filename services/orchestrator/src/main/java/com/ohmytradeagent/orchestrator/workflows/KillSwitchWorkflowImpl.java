package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.KillSwitchState;
import com.ohmytradeagent.contract.KillSwitchWorkflowInput;
import com.ohmytradeagent.contract.ResetKillSwitchRequest;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.TripKillSwitchRequest;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.DailyPnlActivities;
import com.ohmytradeagent.orchestrator.activities.KillSwitchCascadeActivities;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import com.ohmytradeagent.orchestrator.activities.StrategyActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.Workflow;
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

  /** Heartbeat cadence — 60s per PLAN.md kill-switch flow. */
  static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(60);

  /** Fallback cooldown when the strategy config does not set {@code reset_cooldown_secs}. */
  static final long DEFAULT_RESET_COOLDOWN_SECS = 60L;

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
  private KillSwitchWorkflowInput input;
  private boolean tripped;
  private String reason = "";
  private String actor = "";
  private OffsetDateTime trippedAt;
  private OffsetDateTime coolingDownUntil;
  private LocalDate tradingDay;

  @Override
  public String run(KillSwitchWorkflowInput in) {
    if (in.getSchemaVersion() == null || in.getSchemaVersion() > 1L) {
      throw new IllegalArgumentException(
          "KillSwitchWorkflowInput schema_version unsupported: " + in.getSchemaVersion());
    }
    this.input = in;
    this.tradingDay = calendar.todayEt();

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
    }
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
    if (threshold == null || threshold.signum() <= 0) {
      // No threshold configured — auto-trip disabled.
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
}
