package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateException;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class KillSwitchWorkflowImplTest {

  private static final String CORE_QUEUE = "orchestrator-core";
  // Phase 2: v>=1 (TestWorkflowEnvironment reports v==1 for fresh workflows) routes the realized
  // read to the strategy's broker-<target> exec queue. strategyConfig() pins alpaca-paper.
  private static final String BROKER_QUEUE = "broker-alpaca-paper";

  private TestWorkflowEnvironment env;
  private AuditActivities audit;
  private MarketCalendarActivities calendar;
  private StrategyActivities strategy;
  private DailyPnlActivities pnl;
  private DailyPnlExecActivity execPnl;
  private KillSwitchCascadeActivities cascade;
  private long originalHistoryLengthWatermark;

  @BeforeEach
  void setUp() {
    // Capture the production watermark so tests that mutate it can restore it in @AfterEach.
    originalHistoryLengthWatermark = KillSwitchWorkflowImpl.historyLengthWatermark;
    env = TestWorkflowEnvironment.newInstance();
    Worker coreWorker = env.newWorker(CORE_QUEUE);
    coreWorker.registerWorkflowImplementationTypes(KillSwitchWorkflowImpl.class);

    audit = Mockito.mock(AuditActivities.class);
    calendar = Mockito.mock(MarketCalendarActivities.class);
    strategy = Mockito.mock(StrategyActivities.class);
    pnl = Mockito.mock(DailyPnlActivities.class);
    execPnl = Mockito.mock(DailyPnlExecActivity.class);
    cascade = Mockito.mock(KillSwitchCascadeActivities.class);

    // Defaults: market closed (no auto-trip), today=2026-05-14.
    when(calendar.isMarketOpen()).thenReturn(false);
    when(calendar.todayEt()).thenReturn(LocalDate.of(2026, 5, 14));
    when(strategy.get(anyString(), anyString())).thenReturn(strategyConfig());
    when(pnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    // Phase 2: v>=1 realized read now flows through the broker-routed exec activity by default.
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    when(cascade.cascadeRiskBreach(anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(0L);

    coreWorker.registerActivitiesImplementations(audit, calendar, strategy, pnl, cascade);
    // The realized read (v>=1) routes to broker-<target>; register the exec activity on both the
    // paper and live queues so the live-threshold config tests (broker_target=alpaca-live) resolve.
    Worker brokerWorker = env.newWorker(BROKER_QUEUE);
    brokerWorker.registerActivitiesImplementations(execPnl);
    Worker brokerLiveWorker = env.newWorker("broker-alpaca-live");
    brokerLiveWorker.registerActivitiesImplementations(execPnl);
    env.start();
  }

  @AfterEach
  void tearDown() {
    KillSwitchWorkflowImpl.historyLengthWatermark = originalHistoryLengthWatermark;
    env.close();
  }

  @Test
  void tripUpdate_setsStateAndAudits_noAutoFlatten() {
    KillSwitchWorkflow stub = newStub("t-dev/s-copytrade-v1/killswitch");
    WorkflowStub.fromTyped(stub).start(input());

    stub.trip(tripRequest("manual:operator_initiated", "operator:ridopark"));

    KillSwitchState state = stub.killswitchState();
    assertThat(state.getTripped()).isTrue();
    assertThat(state.getReason()).isEqualTo("manual:operator_initiated");
    assertThat(state.getActor()).isEqualTo("operator:ridopark");
    assertThat(state.getTrippedAt()).isNotNull();

    AuditEvent tripped = captureKind("KillSwitchTripped");
    assertThat(tripped.getSubject()).containsEntry("reason", "manual:operator_initiated");
    assertThat(tripped.getSubject()).containsEntry("actor", "operator:ridopark");
    // Phase 2 (PLAN-2026-07-15): the no-auto-flatten policy applies to manual trips too — the trip
    // subject carries flatten=manual and NO cascade is dispatched.
    assertThat(tripped.getSubject()).containsEntry("flatten", "manual");
    verify(cascade, never())
        .cascadeRiskBreach(anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void tripUpdate_whenAlreadyTripped_rejectedByValidator() {
    KillSwitchWorkflow stub = newStub("t-dev/s-copytrade-v1/killswitch-dup");
    WorkflowStub.fromTyped(stub).start(input());

    stub.trip(tripRequest("manual:first", "operator:a"));

    assertThatThrownBy(() -> stub.trip(tripRequest("manual:second", "operator:b")))
        .isInstanceOf(WorkflowUpdateException.class)
        .hasStackTraceContaining("already_tripped");

    // Phase 2 (PLAN-2026-07-15): no auto-flatten (the second trip is rejected by the validator).
    verify(cascade, never())
        .cascadeRiskBreach(anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void resetUpdate_whenNotTripped_rejectedByValidator() {
    KillSwitchWorkflow stub = newStub("t-dev/s-copytrade-v1/killswitch-resetfirst");
    WorkflowStub.fromTyped(stub).start(input());

    assertThatThrownBy(() -> stub.reset(resetRequest("alice", "no trip yet")))
        .isInstanceOf(WorkflowUpdateException.class)
        .hasStackTraceContaining("not_tripped");
  }

  @Test
  void resetUpdate_blankApprover_rejectedByValidator() {
    KillSwitchWorkflow stub = newStub("t-dev/s-copytrade-v1/killswitch-blank");
    WorkflowStub.fromTyped(stub).start(input());
    stub.trip(tripRequest("manual:ops", "operator:c"));

    assertThatThrownBy(() -> stub.reset(resetRequest("", "missing operator")))
        .isInstanceOf(WorkflowUpdateException.class)
        .hasStackTraceContaining("approver_id_1_required");
  }

  @Test
  void resetUpdate_singleOperator_clearsTrippedAndSetsCooldown() {
    // Single-operator reset (approver_id_1 only): untrips, arms cooldown, and emits exactly one
    // KillSwitchResetApproved whose subject carries approver_id_1 + via=manual_reset +
    // cooling_down_until + cooldown_secs and NEVER approver_id_2 (dual control retired).
    KillSwitchWorkflow stub = newStub("t-dev/s-copytrade-v1/killswitch-reset");
    WorkflowStub.fromTyped(stub).start(input());
    stub.trip(tripRequest("manual:ops", "operator:c"));

    stub.reset(resetRequest("alice", "investigation complete"));

    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isFalse();
    assertThat(s.getCoolingDownUntil()).isNotNull();

    AuditEvent reset = captureKind("KillSwitchResetApproved");
    assertThat(reset.getSubject())
        .containsEntry("approver_id_1", "alice")
        .containsEntry("via", "manual_reset")
        .containsEntry("note", "investigation complete")
        .containsKey("cooling_down_until")
        .doesNotContainKey("approver_id_2");
    // Cooldown matches strategy config (60s in strategyConfig()).
    assertThat(((Number) reset.getSubject().get("cooldown_secs")).longValue()).isEqualTo(60L);
  }

  @Test
  void resetOnActivation_onTrippedSwitch_untripsAndAuditsSingleOperator() {
    // The one-click activate path: a single operator untrips the switch (no dual control) so the
    // strategy actually resumes. State clears, the 60s cooldown arms, and the audit is HONEST about
    // being a live-activation reset (via + operator, and NO approver_id_2).
    KillSwitchWorkflow stub = newStub("t-dev/s-copytrade-v1/killswitch-activate-reset");
    WorkflowStub.fromTyped(stub).start(input());
    stub.trip(tripRequest("live_deactivation:one_click", "operator:ridopark"));
    assertThat(stub.killswitchState().getTripped()).isTrue();

    stub.resetOnActivation(resetOnActivationRequest("operator:ridopark", "live_activation"));

    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isFalse();
    assertThat(s.getCoolingDownUntil()).isNotNull();

    AuditEvent reset = captureKind("KillSwitchResetApproved");
    assertThat(reset.getSubject())
        .containsEntry("via", "live_activation")
        .containsEntry("operator", "operator:ridopark")
        .doesNotContainKey("approver_id_2");
    // Cooldown matches strategy config (60s in strategyConfig()).
    assertThat(((Number) reset.getSubject().get("cooldown_secs")).longValue()).isEqualTo(60L);
  }

  @Test
  void resetOnActivation_whenNotTripped_rejectedByValidator() {
    KillSwitchWorkflow stub = newStub("t-dev/s-copytrade-v1/killswitch-activate-nottripped");
    WorkflowStub.fromTyped(stub).start(input());

    assertThatThrownBy(
            () -> stub.resetOnActivation(resetOnActivationRequest("operator:ridopark", null)))
        .isInstanceOf(WorkflowUpdateException.class)
        .hasStackTraceContaining("not_tripped");
  }

  @Test
  void resetOnActivation_blankOperator_rejectedByValidator() {
    KillSwitchWorkflow stub = newStub("t-dev/s-copytrade-v1/killswitch-activate-noop");
    WorkflowStub.fromTyped(stub).start(input());
    stub.trip(tripRequest("live_deactivation:one_click", "operator:ridopark"));

    assertThatThrownBy(() -> stub.resetOnActivation(resetOnActivationRequest("", "no operator")))
        .isInstanceOf(WorkflowUpdateException.class)
        .hasStackTraceContaining("operator_required");
  }

  @Test
  void queryBeforeAnyUpdate_returnsNotTripped() {
    KillSwitchWorkflow stub = newStub("t-dev/s-copytrade-v1/killswitch-query");
    WorkflowStub.fromTyped(stub).start(input());

    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isFalse();
    assertThat(s.getTrippedAt()).isNull();
    assertThat(s.getCoolingDownUntil()).isNull();

    verify(cascade, never())
        .cascadeRiskBreach(anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void heartbeat_autoTripsWhenDailyLossExceeded_fromExecJournal() {
    // Phase 2 (C2): v>=1 sources realized P&L from the broker-routed exec journal, NOT audit_log.
    when(calendar.isMarketOpen()).thenReturn(true);
    // Exec-journal realized (broker truth) = -3000; threshold = 2500. -3000 <= -2500 -> auto-trip.
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-3000"));

    KillSwitchWorkflow stub = newStub("t-dev/s-copytrade-v1/killswitch-autotrip");
    WorkflowStub.fromTyped(stub).start(input());

    // Skip the first heartbeat tick.
    env.sleep(Duration.ofSeconds(75));

    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isTrue();
    assertThat(s.getActor()).isEqualTo("auto:daily_loss");
    assertThat(s.getReason()).isEqualTo("auto:daily_loss");

    // Phase 2 (PLAN-2026-07-15): a fresh auto-trip HALTS + pages but does NOT auto-flatten — the
    // cascade is never dispatched and the trip subject carries flatten=manual.
    verify(cascade, never())
        .cascadeRiskBreach(anyString(), anyString(), anyString(), anyString(), anyString());
    AuditEvent tripped = captureKind("KillSwitchTripped");
    assertThat(tripped.getSubject()).containsEntry("flatten", "manual");
    // The audit_log path is NOT consulted on v>=1 — broker truth wins.
    verify(pnl, never()).computeRealizedPnl(anyString(), anyString(), any());
  }

  @Test
  void heartbeat_execReadFailure_doesNotTrip_thenAlertsAfterThreshold()
      throws InterruptedException {
    // Phase 2 (C6 / G1): an exec-activity failure on v>=1 must NOT doTrip that tick (a missing P&L
    // number is not a loss). After REALIZED_READ_FAILURE_ALERT_TICKS consecutive failures the
    // heartbeat emits ONE bounded alert with the distinct reason — never a spurious trip.
    when(calendar.isMarketOpen()).thenReturn(true);
    // The exec activity fails on every attempt (exhausts the stub's bounded retry each tick).
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenThrow(new IllegalStateException("exec journal unavailable"));

    KillSwitchWorkflow stub = newStub("t-dev/s-copytrade-v1/killswitch-execdown");
    WorkflowStub.fromTyped(stub).start(input());

    // Run past REALIZED_READ_FAILURE_ALERT_TICKS (3) heartbeats (60s cadence).
    env.sleep(Duration.ofSeconds(4 * 60));

    // NEVER tripped on a missing number, and the bounded alert fired exactly once.
    assertThat(stub.killswitchState().getTripped()).isFalse();
    verify(cascade, never())
        .cascadeRiskBreach(anyString(), anyString(), anyString(), anyString(), anyString());
    // Deterministic sync: the bounded alert is emitted on the activity worker thread; wait for it
    // to
    // be visible before asserting the exact count (guards the time-skip/real-thread race under
    // load).
    waitForAuditKind("KillSwitchRealizedReadUnavailable");
    assertThat(countKind("KillSwitchRealizedReadUnavailable")).isEqualTo(1L);
  }

  @Test
  void heartbeatLoop_continuesAsNew_carryingTrippedStateAcross() {
    // Lower the production watermark so a small number of heartbeat ticks crosses it inside the
    // TestWorkflowEnvironment. Production keeps the 10_000-event default; the test only mutates
    // the in-process field for its own lifetime (restored in @AfterEach).
    KillSwitchWorkflowImpl.historyLengthWatermark = 50L;

    String workflowId = "t-dev/s-copytrade-v1/killswitch-can";
    KillSwitchWorkflow stub = newStub(workflowId);
    WorkflowStub typed = WorkflowStub.fromTyped(stub);
    typed.start(input());
    String runIdBeforeCarry = typed.getExecution().getRunId();

    // Trip before the continueAsNew boundary so we can assert the state carries across.
    stub.trip(tripRequest("manual:before_can", "operator:test"));
    assertThat(stub.killswitchState().getTripped()).isTrue();

    // Drive enough heartbeat ticks for history-length to exceed the (lowered) watermark. Each
    // tick adds several events (timer + activity schedule/complete + workflow task); 30 ticks is
    // ample headroom above 50 events and still completes well inside the per-phase 60s budget
    // because TestWorkflowEnvironment skips real time.
    env.sleep(Duration.ofMinutes(30));

    // After the continueAsNew boundary, queries on the workflow id reach the *new* run. If the
    // tripped flag is false here, state did not carry forward — that is the failure mode this
    // test is guarding against.
    KillSwitchWorkflow stubAfter =
        env.getWorkflowClient().newWorkflowStub(KillSwitchWorkflow.class, workflowId);
    KillSwitchState after = stubAfter.killswitchState();
    assertThat(after.getTripped()).isTrue();
    assertThat(after.getReason()).isEqualTo("manual:before_can");
    assertThat(after.getActor()).isEqualTo("operator:test");

    // Confirm the workflow actually crossed the continueAsNew boundary (the run id rotated).
    // Without this assertion the test could pass even if continueAsNew never fired (the original
    // run would still hold tripped=true). describe() returns the current run id for the
    // workflow id from Temporal's visibility tables.
    String runIdAfterCarry =
        env.getWorkflowClient()
            .newUntypedWorkflowStub(workflowId)
            .describe()
            .getExecution()
            .getRunId();
    assertThat(runIdAfterCarry).isNotEqualTo(runIdBeforeCarry);

    // Audit activities continue to be invoked in the new run (calendar.todayEt fires each tick).
    verify(audit, atLeastOnce()).log(any());
  }

  // ---------- B2 (P0c-b1): live kill-switch heartbeat floor ----------

  @Test
  void heartbeat_liveWithNullThreshold_tripsWithDistinctReason() throws InterruptedException {
    // Market open + a LIVE strategy whose daily_loss_threshold is null (an upstream control was
    // bypassed). The heartbeat must fail closed: trip with the distinct anomaly reason.
    when(calendar.isMarketOpen()).thenReturn(true);
    when(strategy.get(anyString(), anyString())).thenReturn(liveNullThresholdConfig());

    KillSwitchWorkflow stub = newStub("t-dev/s-copytrade-live/killswitch-livenull");
    WorkflowStub.fromTyped(stub).start(input());

    // Skip the first heartbeat tick (60s interval).
    env.sleep(Duration.ofSeconds(75));

    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isTrue();
    assertThat(s.getReason()).isEqualTo("auto:missing_loss_threshold");
    assertThat(s.getActor()).isEqualTo("auto:missing_loss_threshold");

    // Deterministic sync: the trip audit is emitted on the activity worker thread during the
    // skipped
    // heartbeat tick; wait for it before the instantaneous captor read below.
    waitForAuditKind("KillSwitchTripped");
    AuditEvent tripped = captureKind("KillSwitchTripped");
    assertThat(tripped.getSubject()).containsEntry("reason", "auto:missing_loss_threshold");
    // Anomaly trip carries no quantified value (null pnl was never computed).
    assertThat(tripped.getSubject()).doesNotContainKey("value");

    // pnl is never computed on the anomaly path — the trip short-circuits before the pnl Activity.
    verify(pnl, never()).computeRealizedPnl(anyString(), anyString(), any());
  }

  @Test
  void heartbeat_paperWithNullThreshold_doesNotTrip() {
    // PAPER strategy with a null threshold preserves the original opt-out: no auto-trip.
    when(calendar.isMarketOpen()).thenReturn(true);
    when(strategy.get(anyString(), anyString())).thenReturn(paperNullThresholdConfig());

    KillSwitchWorkflow stub = newStub("t-dev/s-copytrade-v1/killswitch-papernull");
    WorkflowStub.fromTyped(stub).start(input());

    env.sleep(Duration.ofSeconds(75));

    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isFalse();
    verify(cascade, never())
        .cascadeRiskBreach(anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void heartbeat_liveWithValidThreshold_unchanged() {
    // LIVE strategy + valid threshold + non-breaching pnl: the normal path is unchanged — no trip.
    when(calendar.isMarketOpen()).thenReturn(true);
    StrategyConfig live = liveNullThresholdConfig();
    live.setDailyLossThreshold(new BigDecimal("2500.00"));
    when(strategy.get(anyString(), anyString())).thenReturn(live);
    when(execPnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-100"));

    KillSwitchWorkflow stub = newStub("t-dev/s-copytrade-live/killswitch-livevalid");
    WorkflowStub.fromTyped(stub).start(input());

    env.sleep(Duration.ofSeconds(75));

    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isFalse();
  }

  // ---------- helpers ----------

  private KillSwitchWorkflow newStub(String workflowId) {
    return env.getWorkflowClient()
        .newWorkflowStub(
            KillSwitchWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(CORE_QUEUE)
                .setWorkflowId(workflowId)
                .build());
  }

  private static KillSwitchWorkflowInput input() {
    KillSwitchWorkflowInput in = new KillSwitchWorkflowInput();
    in.setSchemaVersion(1L);
    in.setTenantId("dev");
    in.setStrategyId("copytrade-v1");
    return in;
  }

  private static TripKillSwitchRequest tripRequest(String reason, String actor) {
    TripKillSwitchRequest r = new TripKillSwitchRequest();
    r.setSchemaVersion(1L);
    r.setReason(reason);
    r.setActor(actor);
    return r;
  }

  private static ResetKillSwitchRequest resetRequest(String a1, String note) {
    ResetKillSwitchRequest r = new ResetKillSwitchRequest();
    r.setSchemaVersion(1L);
    r.setApproverId1(a1);
    r.setNote(note);
    return r;
  }

  private static ResetKillSwitchRequest resetOnActivationRequest(String operator, String note) {
    ResetKillSwitchRequest r = new ResetKillSwitchRequest();
    r.setSchemaVersion(1L);
    r.setApproverId1(operator);
    if (note != null) {
      r.setNote(note);
    }
    return r;
  }

  private static StrategyConfig strategyConfig() {
    StrategyConfig c = new StrategyConfig();
    c.setSchemaVersion(1L);
    c.setTenantId("dev");
    c.setStrategyId("copytrade-v1");
    // Phase 2: routable broker_target so the v>=1 realized read reaches broker-alpaca-paper (the
    // legacy bare "paper" has no worker queue and taskQueueFor rejects it).
    c.setBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_PAPER);
    c.setAuthorWhitelist(Set.of("acme_trader"));
    // Issue #3: per-side signal-age defaults replace the legacy 1800s default.
    c.setMaxSignalAgeBtoSecs(3600L);
    c.setMaxSignalAgeStcSecs(3600L);
    c.setMaxPositions(5L);
    c.setCapitalWeight(new BigDecimal("0.2"));
    c.setDailyLossThreshold(new BigDecimal("2500.00"));
    c.setResetCooldownSecs(60L);
    return c;
  }

  /**
   * B2 (P0c-b1): a LIVE strategy (broker_target ends with {@code -live}) whose {@code
   * daily_loss_threshold} is null — the anomaly the heartbeat floor fails closed on.
   */
  private static StrategyConfig liveNullThresholdConfig() {
    StrategyConfig c = strategyConfig();
    c.setBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_LIVE);
    c.setDailyLossThreshold(null);
    return c;
  }

  /** B2: a PAPER strategy with a null {@code daily_loss_threshold} — the preserved opt-out path. */
  private static StrategyConfig paperNullThresholdConfig() {
    StrategyConfig c = strategyConfig();
    c.setBrokerTarget(StrategyConfig.BrokerTarget.PAPER);
    c.setDailyLossThreshold(null);
    return c;
  }

  private AuditEvent captureKind(String kind) {
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(captor.capture());
    return captor.getAllValues().stream()
        .filter(e -> kind.equals(e.getKind()))
        .reduce((a, b) -> b)
        .orElseThrow(() -> new AssertionError("no audit event with kind=" + kind));
  }

  /** Counts audit events of {@code kind} logged so far (0 if none) — never throws. */
  private long countKind(String kind) {
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    Mockito.verify(audit, Mockito.atLeast(0)).log(captor.capture());
    return captor.getAllValues().stream().filter(e -> kind.equals(e.getKind())).count();
  }

  /**
   * Deterministic sync point for async audit emissions. Heartbeat-driven audit events are emitted
   * on the activity worker thread while the workflow clock is skipped by {@link
   * TestWorkflowEnvironment#sleep}; under CI load the skip can return before the last tick's {@code
   * audit.log} invocation is visible to this (test) thread, making an instantaneous {@link
   * #captureKind}/{@link #countKind} read flaky. Poll (bounded) until at least {@code atLeast}
   * events of {@code kind} have been captured before asserting on them.
   */
  private void waitForKindCount(String kind, long atLeast) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      if (countKind(kind) >= atLeast) {
        return;
      }
      Thread.sleep(25);
    }
    if (countKind(kind) < atLeast) {
      throw new AssertionError(
          "timed out waiting for >=" + atLeast + " audit event(s) with kind=" + kind);
    }
  }

  /** Bounded wait for the first audit event of {@code kind} (see {@link #waitForKindCount}). */
  private void waitForAuditKind(String kind) throws InterruptedException {
    waitForKindCount(kind, 1L);
  }
}
