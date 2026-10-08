package com.ohmytradeagent.orchestrator.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.KillSwitchState;
import com.ohmytradeagent.contract.ResetKillSwitchRequest;
import com.ohmytradeagent.contract.TripKillSwitchRequest;
import com.ohmytradeagent.contract.activities.AccountSnapshotActivity;
import com.ohmytradeagent.contract.activities.DailyPnlExecActivity;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.activities.AccountKillSwitchCascadeActivities;
import com.ohmytradeagent.orchestrator.activities.AccountPnlActivities;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.DailyPnlActivities;
import com.ohmytradeagent.orchestrator.activities.GetOptionQuoteActivity;
import com.ohmytradeagent.orchestrator.activities.KillSwitchCascadeActivities;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import com.ohmytradeagent.orchestrator.activities.StrategyActivities;
import com.ohmytradeagent.orchestrator.activities.TenantConfigActivities;
import com.ohmytradeagent.orchestrator.workflows.AccountKillSwitchWorkflow;
import com.ohmytradeagent.orchestrator.workflows.AccountKillSwitchWorkflowImpl;
import com.ohmytradeagent.orchestrator.workflows.KillSwitchWorkflow;
import com.ohmytradeagent.orchestrator.workflows.KillSwitchWorkflowImpl;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Issue #911 (risk R1): recreating a terminated kill switch must carry the closed run's trip state
 * into the new run, never start it untripped by default. Runs the REAL workflow impls so the carry
 * goes through their {@code @WorkflowInit} hydration, and asserts on the {@code killswitch_state} /
 * {@code account_killswitch_state} query — the exact read {@code RiskActivitiesImpl}'s pre-trade
 * kill-switch check rejects on ({@code tripped} → KILL_SWITCH_TRIPPED, {@code cooling_down_until}
 * in the future → KILL_SWITCH_COOLING_DOWN).
 */
class KillSwitchBootstrapperCarryForwardTest {

  private static final String TENANT = "dev";
  private static final String STRATEGY = "copytrade-v1";

  private TestWorkflowEnvironment env;
  private WorkflowClient client;
  private final List<AuditEvent> pages = new CopyOnWriteArrayList<>();
  private KillSwitchBootstrapper bootstrapper;

  @BeforeEach
  void setUp() {
    env = TestWorkflowEnvironment.newInstance();
    env.registerSearchAttribute("TenantStrategy", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
    Worker core = env.newWorker(KillSwitchBootstrapper.KILLSWITCH_TASK_QUEUE);
    core.registerWorkflowImplementationTypes(
        KillSwitchWorkflowImpl.class, AccountKillSwitchWorkflowImpl.class);

    // Market closed: no heartbeat evaluates, so state changes only through the updates below.
    MarketCalendarActivities calendar = mock(MarketCalendarActivities.class);
    when(calendar.isMarketOpen()).thenReturn(false);
    when(calendar.todayEt()).thenReturn(LocalDate.of(2026, 10, 6));
    TenantConfigActivities tenantConfig = mock(TenantConfigActivities.class);
    when(tenantConfig.tenantBrokerTarget(anyString())).thenReturn("alpaca-paper");
    core.registerActivitiesImplementations(
        mock(AuditActivities.class),
        calendar,
        mock(StrategyActivities.class),
        mock(DailyPnlActivities.class),
        mock(KillSwitchCascadeActivities.class),
        tenantConfig,
        mock(AccountPnlActivities.class),
        mock(AccountKillSwitchCascadeActivities.class));
    env.newWorker("market-data")
        .registerActivitiesImplementations(mock(GetOptionQuoteActivity.class));
    env.newWorker("broker-alpaca-paper")
        .registerActivitiesImplementations(
            mock(AccountSnapshotActivity.class), mock(DailyPnlExecActivity.class));
    env.start();
    client = env.getWorkflowClient();
    bootstrapper = new KillSwitchBootstrapper(client, pages::add, "does-not-exist");
  }

  @AfterEach
  void tearDown() {
    env.close();
  }

  /**
   * (a) A terminated, TRIPPED per-strategy switch comes back tripped with the same reason/actor.
   */
  @Test
  void terminatedTrippedStrategySwitchIsRecreatedStillTripped() {
    assertThat(bootstrapper.ensureStrategyKillSwitch(TENANT, STRATEGY)).isTrue();
    String wf = WorkflowIds.killswitch(TENANT, STRATEGY);
    KillSwitchWorkflow ks = client.newWorkflowStub(KillSwitchWorkflow.class, wf);
    ks.trip(trip("manual:operator_halt", "operator:ridopark"));
    KillSwitchState before = ks.killswitchState();
    awaitIdle(wf);
    client.newUntypedWorkflowStub(wf).terminate("operator");

    assertThat(bootstrapper.ensureStrategyKillSwitch(TENANT, STRATEGY)).isTrue();

    KillSwitchState after = client.newWorkflowStub(KillSwitchWorkflow.class, wf).killswitchState();
    assertThat(after.getTripped()).isTrue();
    assertThat(after.getReason()).isEqualTo("manual:operator_halt");
    assertThat(after.getActor()).isEqualTo("operator:ridopark");
    assertThat(after.getTrippedAt()).isEqualTo(before.getTrippedAt());
    assertThat(pages)
        .singleElement()
        .satisfies(
            e ->
                assertThat(e.getSubject())
                    .containsEntry("recreated", true)
                    .containsEntry("tripped", true)
                    .containsEntry("state_unknown", false));
  }

  /** (b) Same at account scope. */
  @Test
  void terminatedTrippedAccountSwitchIsRecreatedStillTripped() {
    assertThat(bootstrapper.ensureAccountKillSwitch(TENANT)).isTrue();
    String wf = WorkflowIds.accountKillswitch(TENANT);
    AccountKillSwitchWorkflow ks = client.newWorkflowStub(AccountKillSwitchWorkflow.class, wf);
    ks.trip(trip("manual:operator_initiated", "operator:ridopark"));
    awaitIdle(wf);
    client.newUntypedWorkflowStub(wf).terminate("operator");

    assertThat(bootstrapper.ensureAccountKillSwitch(TENANT)).isTrue();

    KillSwitchState after =
        client.newWorkflowStub(AccountKillSwitchWorkflow.class, wf).killswitchState();
    assertThat(after.getTripped()).isTrue();
    assertThat(after.getReason()).isEqualTo("manual:operator_initiated");
    assertThat(after.getActor()).isEqualTo("operator:ridopark");
  }

  /** (b) A reset account switch still cooling down comes back cooling down until the same time. */
  @Test
  void terminatedCoolingDownAccountSwitchKeepsItsCooldown() {
    assertThat(bootstrapper.ensureAccountKillSwitch(TENANT)).isTrue();
    String wf = WorkflowIds.accountKillswitch(TENANT);
    AccountKillSwitchWorkflow ks = client.newWorkflowStub(AccountKillSwitchWorkflow.class, wf);
    ks.trip(trip("manual:operator_initiated", "operator:ridopark"));
    ResetKillSwitchRequest reset = new ResetKillSwitchRequest();
    reset.setSchemaVersion(1L);
    reset.setApproverId1("operator:ridopark");
    ks.reset(reset);
    KillSwitchState before = ks.killswitchState();
    assertThat(before.getCoolingDownUntil()).isNotNull();
    awaitIdle(wf);
    client.newUntypedWorkflowStub(wf).terminate("operator");

    assertThat(bootstrapper.ensureAccountKillSwitch(TENANT)).isTrue();

    KillSwitchState after =
        client.newWorkflowStub(AccountKillSwitchWorkflow.class, wf).killswitchState();
    assertThat(after.getTripped()).isFalse();
    assertThat(after.getCoolingDownUntil()).isEqualTo(before.getCoolingDownUntil());
  }

  /**
   * Waits (bounded, no sleep) until the trip/reset's async activities have finished and no workflow
   * task is mid-flight, so the terminate lands on a quiescent history — the shape a real terminate
   * between heartbeats has. A history cut mid-task cannot be replayed, so its query fails (the
   * bootstrapper's unknown-state path, covered in KillSwitchBootstrapperTest).
   */
  private void awaitIdle(String wf) {
    Set<EventType> busy =
        Set.of(
            EventType.EVENT_TYPE_WORKFLOW_TASK_SCHEDULED,
            EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED,
            EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED,
            EventType.EVENT_TYPE_ACTIVITY_TASK_STARTED,
            EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED);
    for (int i = 0; i < 10_000; i++) {
      // Describe FIRST, then confirm the history did not move since: no pending activity, no
      // workflow task in flight, and nothing happened between the two reads.
      DescribeWorkflowExecutionResponse d = describe(wf);
      List<HistoryEvent> events = client.fetchHistory(wf).getHistory().getEventsList();
      EventType last = events.get(events.size() - 1).getEventType();
      if (d.getPendingActivitiesCount() == 0
          && events.size() == d.getWorkflowExecutionInfo().getHistoryLength()
          && !busy.contains(last)) {
        return;
      }
      Thread.onSpinWait();
    }
    throw new AssertionError("workflow " + wf + " never went idle");
  }

  private DescribeWorkflowExecutionResponse describe(String wf) {
    return client
        .getWorkflowServiceStubs()
        .blockingStub()
        .describeWorkflowExecution(
            DescribeWorkflowExecutionRequest.newBuilder()
                .setNamespace(client.getOptions().getNamespace())
                .setExecution(WorkflowExecution.newBuilder().setWorkflowId(wf).build())
                .build());
  }

  private static TripKillSwitchRequest trip(String reason, String actor) {
    TripKillSwitchRequest r = new TripKillSwitchRequest();
    r.setSchemaVersion(1L);
    r.setReason(reason);
    r.setActor(actor);
    return r;
  }
}
