package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

  private TestWorkflowEnvironment env;
  private AuditActivities audit;
  private MarketCalendarActivities calendar;
  private StrategyActivities strategy;
  private DailyPnlActivities pnl;
  private KillSwitchCascadeActivities cascade;

  @BeforeEach
  void setUp() {
    env = TestWorkflowEnvironment.newInstance();
    Worker coreWorker = env.newWorker(CORE_QUEUE);
    coreWorker.registerWorkflowImplementationTypes(KillSwitchWorkflowImpl.class);

    audit = Mockito.mock(AuditActivities.class);
    calendar = Mockito.mock(MarketCalendarActivities.class);
    strategy = Mockito.mock(StrategyActivities.class);
    pnl = Mockito.mock(DailyPnlActivities.class);
    cascade = Mockito.mock(KillSwitchCascadeActivities.class);

    // Defaults: market closed (no auto-trip), today=2026-05-14.
    when(calendar.isMarketOpen()).thenReturn(false);
    when(calendar.todayEt()).thenReturn(LocalDate.of(2026, 5, 14));
    when(strategy.get(anyString(), anyString())).thenReturn(strategyConfig());
    when(pnl.computeRealizedPnl(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    when(cascade.cascadeRiskBreach(anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(0L);

    coreWorker.registerActivitiesImplementations(audit, calendar, strategy, pnl, cascade);
    env.start();
  }

  @AfterEach
  void tearDown() {
    env.close();
  }

  @Test
  void tripUpdate_setsStateAndAuditsAndCascades() {
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

    // Cascade Activity invoked exactly once, excluding the kill-switch workflow itself.
    verify(cascade, times(1))
        .cascadeRiskBreach(
            eq("dev"),
            eq("copytrade-v1"),
            eq("t-dev/s-copytrade-v1/killswitch"),
            eq("manual:operator_initiated"),
            eq("operator:ridopark"));
  }

  @Test
  void tripUpdate_whenAlreadyTripped_rejectedByValidator() {
    KillSwitchWorkflow stub = newStub("t-dev/s-copytrade-v1/killswitch-dup");
    WorkflowStub.fromTyped(stub).start(input());

    stub.trip(tripRequest("manual:first", "operator:a"));

    assertThatThrownBy(() -> stub.trip(tripRequest("manual:second", "operator:b")))
        .isInstanceOf(WorkflowUpdateException.class)
        .hasStackTraceContaining("already_tripped");

    // Cascade fired exactly once (first trip), not twice.
    verify(cascade, times(1))
        .cascadeRiskBreach(anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void resetUpdate_whenNotTripped_rejectedByValidator() {
    KillSwitchWorkflow stub = newStub("t-dev/s-copytrade-v1/killswitch-resetfirst");
    WorkflowStub.fromTyped(stub).start(input());

    assertThatThrownBy(() -> stub.reset(resetRequest("alice", "bob", "no trip yet")))
        .isInstanceOf(WorkflowUpdateException.class)
        .hasStackTraceContaining("not_tripped");
  }

  @Test
  void resetUpdate_sameApprovers_rejectedByValidator() {
    KillSwitchWorkflow stub = newStub("t-dev/s-copytrade-v1/killswitch-sameappr");
    WorkflowStub.fromTyped(stub).start(input());
    stub.trip(tripRequest("manual:ops", "operator:c"));

    assertThatThrownBy(() -> stub.reset(resetRequest("alice", "alice", "dual control fail")))
        .isInstanceOf(WorkflowUpdateException.class)
        .hasStackTraceContaining("approvers_must_differ");
  }

  @Test
  void resetUpdate_blankApprover_rejectedByValidator() {
    KillSwitchWorkflow stub = newStub("t-dev/s-copytrade-v1/killswitch-blank");
    WorkflowStub.fromTyped(stub).start(input());
    stub.trip(tripRequest("manual:ops", "operator:c"));

    assertThatThrownBy(() -> stub.reset(resetRequest("alice", "", "missing")))
        .isInstanceOf(WorkflowUpdateException.class)
        .hasStackTraceContaining("approver_id_2_required");
  }

  @Test
  void resetUpdate_distinctApprovers_clearsTrippedAndSetsCooldown() {
    KillSwitchWorkflow stub = newStub("t-dev/s-copytrade-v1/killswitch-reset");
    WorkflowStub.fromTyped(stub).start(input());
    stub.trip(tripRequest("manual:ops", "operator:c"));

    stub.reset(resetRequest("alice", "bob", "investigation complete"));

    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isFalse();
    assertThat(s.getCoolingDownUntil()).isNotNull();

    AuditEvent reset = captureKind("KillSwitchResetApproved");
    assertThat(reset.getSubject())
        .containsEntry("approver_id_1", "alice")
        .containsEntry("approver_id_2", "bob")
        .containsEntry("note", "investigation complete");
    // Cooldown matches strategy config (60s in strategyConfig()).
    assertThat(((Number) reset.getSubject().get("cooldown_secs")).longValue()).isEqualTo(60L);
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
  void heartbeat_autoTripsWhenDailyLossExceeded() {
    when(calendar.isMarketOpen()).thenReturn(true);
    // Realized PnL = -3000 dollars; threshold = 2500. -3000 <= -2500 -> auto-trip.
    when(pnl.computeRealizedPnl(anyString(), anyString(), any()))
        .thenReturn(new BigDecimal("-3000"));

    KillSwitchWorkflow stub = newStub("t-dev/s-copytrade-v1/killswitch-autotrip");
    WorkflowStub.fromTyped(stub).start(input());

    // Skip the first heartbeat tick.
    env.sleep(Duration.ofSeconds(75));

    KillSwitchState s = stub.killswitchState();
    assertThat(s.getTripped()).isTrue();
    assertThat(s.getActor()).isEqualTo("auto:daily_loss");
    assertThat(s.getReason()).isEqualTo("auto:daily_loss");

    verify(cascade, atLeastOnce())
        .cascadeRiskBreach(
            eq("dev"),
            eq("copytrade-v1"),
            anyString(),
            eq("auto:daily_loss"),
            eq("auto:daily_loss"));
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

  private static ResetKillSwitchRequest resetRequest(String a1, String a2, String note) {
    ResetKillSwitchRequest r = new ResetKillSwitchRequest();
    r.setSchemaVersion(1L);
    r.setApproverId1(a1);
    r.setApproverId2(a2);
    r.setNote(note);
    return r;
  }

  private static StrategyConfig strategyConfig() {
    StrategyConfig c = new StrategyConfig();
    c.setSchemaVersion(1L);
    c.setTenantId("dev");
    c.setStrategyId("copytrade-v1");
    c.setBrokerTarget(StrategyConfig.BrokerTarget.PAPER);
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

  private AuditEvent captureKind(String kind) {
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, atLeastOnce()).log(captor.capture());
    return captor.getAllValues().stream()
        .filter(e -> kind.equals(e.getKind()))
        .reduce((a, b) -> b)
        .orElseThrow(() -> new AssertionError("no audit event with kind=" + kind));
  }
}
