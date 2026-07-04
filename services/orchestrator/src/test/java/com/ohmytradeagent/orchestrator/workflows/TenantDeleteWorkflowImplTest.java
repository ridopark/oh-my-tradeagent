package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.BrokerOpenOrder;
import com.ohmytradeagent.contract.BrokerPosition;
import com.ohmytradeagent.contract.activities.ReconciliationExecActivity;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.TenantDeleteActivities;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * Phase 4 (PLAN-2026-07-03-operator-tenant-delete) coverage — the REAL-MONEY safety gate. The
 * workflow now evaluates the two orchestrator-reachable live-safety gates FIRST (P4
 * BROKER_NOT_FLAT, P5 HAS_TRADE_HISTORY) and only runs the teardown when both pass. Proven here
 * with mock Activities: the P4/P5 {@link ReconciliationExecActivity} on the {@code broker-<target>}
 * worker, and the {@link TenantDeleteActivities} teardown + {@link AuditActivities} on the
 * orchestrator-core worker.
 *
 * <ul>
 *   <li>P4 not-flat → BLOCKED(BROKER_NOT_FLAT), ZERO teardown, TenantDeleteBlocked audited;
 *   <li>P5 journal-nonempty → BLOCKED(HAS_TRADE_HISTORY), ZERO teardown;
 *   <li>both pass → teardown a→b→c runs and returns COMPLETED with step (c)'s count;
 *   <li>a broker/journal read fault → BLOCKED (fail-closed), ZERO teardown.
 * </ul>
 */
class TenantDeleteWorkflowImplTest {

  private static final String CORE_QUEUE = "orchestrator-core";
  private static final String BROKER_TARGET = "alpaca-paper";
  private static final String BROKER_QUEUE = "broker-alpaca-paper";
  private static final String TENANT = "dev";
  private static final String STRATEGY = "copytrade-v1";
  private static final String ACTOR = "operator:ridopark";

  private TestWorkflowEnvironment env;

  @AfterEach
  void tearDown() {
    if (env != null) {
      env.close();
    }
  }

  /**
   * Stands up a fresh env with the teardown + audit Activities on orchestrator-core and the P4/P5
   * gate Activity on the broker queue, and returns a workflow stub.
   */
  private TenantDeleteWorkflow startWith(
      TenantDeleteActivities activities, AuditActivities audit, ReconciliationExecActivity exec) {
    env = TestWorkflowEnvironment.newInstance();
    Worker core = env.newWorker(CORE_QUEUE);
    core.registerWorkflowImplementationTypes(TenantDeleteWorkflowImpl.class);
    core.registerActivitiesImplementations(activities, audit);
    Worker broker = env.newWorker(BROKER_QUEUE);
    broker.registerActivitiesImplementations(exec);
    env.start();
    return env.getWorkflowClient()
        .newWorkflowStub(
            TenantDeleteWorkflow.class,
            WorkflowOptions.newBuilder().setTaskQueue(CORE_QUEUE).build());
  }

  /** A gate Activity that reports a flat, never-traded broker/journal (both gates pass). */
  private ReconciliationExecActivity flatAndUntraded() {
    ReconciliationExecActivity exec = mock(ReconciliationExecActivity.class);
    when(exec.brokerListOpenPositions(TENANT, STRATEGY)).thenReturn(List.of());
    when(exec.brokerListOpenOrders(TENANT, STRATEGY)).thenReturn(List.of());
    when(exec.journalCountByTenant(TENANT, STRATEGY)).thenReturn(0L);
    return exec;
  }

  @Test
  void bothGatesPass_runsTeardownInOrder_returnsCompleted() {
    TenantDeleteActivities activities = mock(TenantDeleteActivities.class);
    AuditActivities audit = mock(AuditActivities.class);
    when(activities.deleteStrategyConfig(TENANT, STRATEGY, ACTOR)).thenReturn(1);

    TenantDeleteResult result =
        startWith(activities, audit, flatAndUntraded())
            .deleteTenant(TENANT, STRATEGY, BROKER_TARGET, ACTOR);

    assertThat(result.getStatus()).isEqualTo(TenantDeleteResult.Status.COMPLETED);
    assertThat(result.getBlockedBy()).isNull();
    assertThat(result.getDeletedConfigRows()).isEqualTo(1);
    InOrder ordered = inOrder(activities);
    ordered.verify(activities).deleteReconSchedules(TENANT, STRATEGY);
    ordered.verify(activities).terminateKillSwitchWorkflow(TENANT, STRATEGY);
    // The tenant-level account kill-switch is reaped too (FIX 1: staging-paper-2 left it orphaned).
    ordered.verify(activities).terminateAccountKillSwitchWorkflow(TENANT);
    ordered.verify(activities).deleteStrategyConfig(TENANT, STRATEGY, ACTOR);
    // A COMPLETED delete emits no TenantDeleteBlocked (the TenantDeleted tombstone is written
    // inside
    // the config-delete activity, not here).
    verify(audit, never()).log(any());
  }

  @Test
  void p4BrokerNotFlat_blocks_zeroTeardown_andAudits() {
    TenantDeleteActivities activities = mock(TenantDeleteActivities.class);
    AuditActivities audit = mock(AuditActivities.class);
    ReconciliationExecActivity exec = mock(ReconciliationExecActivity.class);
    // A single open position → broker is NOT flat. journalCount is irrelevant (P4 short-circuits).
    when(exec.brokerListOpenPositions(TENANT, STRATEGY)).thenReturn(List.of(new BrokerPosition()));
    when(exec.brokerListOpenOrders(TENANT, STRATEGY)).thenReturn(List.of());

    TenantDeleteResult result =
        startWith(activities, audit, exec).deleteTenant(TENANT, STRATEGY, BROKER_TARGET, ACTOR);

    assertThat(result.getStatus()).isEqualTo(TenantDeleteResult.Status.BLOCKED);
    assertThat(result.getBlockedBy()).isEqualTo(TenantDeleteResult.BlockReason.BROKER_NOT_FLAT);
    assertThat(result.getDeletedConfigRows()).isZero();
    verifyNoInteractions(activities);
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, times(1)).log(captor.capture());
    AuditEvent emitted = captor.getValue();
    assertThat(emitted.getKind()).isEqualTo("TenantDeleteBlocked");
    assertThat(emitted.getSubject()).containsEntry("blocked_by", "BROKER_NOT_FLAT");
  }

  @Test
  void p4BrokerNotFlat_openOrder_blocks() {
    TenantDeleteActivities activities = mock(TenantDeleteActivities.class);
    AuditActivities audit = mock(AuditActivities.class);
    ReconciliationExecActivity exec = mock(ReconciliationExecActivity.class);
    when(exec.brokerListOpenPositions(TENANT, STRATEGY)).thenReturn(List.of());
    // A pending open order (no position) → still NOT flat.
    when(exec.brokerListOpenOrders(TENANT, STRATEGY)).thenReturn(List.of(new BrokerOpenOrder()));

    TenantDeleteResult result =
        startWith(activities, audit, exec).deleteTenant(TENANT, STRATEGY, BROKER_TARGET, ACTOR);

    assertThat(result.getBlockedBy()).isEqualTo(TenantDeleteResult.BlockReason.BROKER_NOT_FLAT);
    verifyNoInteractions(activities);
  }

  @Test
  void p4NullPositions_blocks_failClosed_zeroTeardown() {
    TenantDeleteActivities activities = mock(TenantDeleteActivities.class);
    AuditActivities audit = mock(AuditActivities.class);
    ReconciliationExecActivity exec = mock(ReconciliationExecActivity.class);
    // A NULL positions read is an UNKNOWN broker state, not proof of flatness — never treat an
    // unknown/null read as flat. Fail closed to BLOCKED with ZERO teardown.
    when(exec.brokerListOpenPositions(TENANT, STRATEGY)).thenReturn(null);
    when(exec.brokerListOpenOrders(TENANT, STRATEGY)).thenReturn(List.of());

    TenantDeleteResult result =
        startWith(activities, audit, exec).deleteTenant(TENANT, STRATEGY, BROKER_TARGET, ACTOR);

    assertThat(result.getStatus()).isEqualTo(TenantDeleteResult.Status.BLOCKED);
    assertThat(result.getBlockedBy()).isEqualTo(TenantDeleteResult.BlockReason.BROKER_NOT_FLAT);
    verifyNoInteractions(activities);
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, times(1)).log(captor.capture());
    assertThat(captor.getValue().getSubject().get("detail").toString())
        .contains("null positions")
        .contains("fail-closed");
  }

  @Test
  void p4NullOrders_blocks_failClosed_zeroTeardown() {
    TenantDeleteActivities activities = mock(TenantDeleteActivities.class);
    AuditActivities audit = mock(AuditActivities.class);
    ReconciliationExecActivity exec = mock(ReconciliationExecActivity.class);
    // Positions read is a (non-null) flat list, but the orders read is NULL → UNKNOWN → fail
    // closed.
    when(exec.brokerListOpenPositions(TENANT, STRATEGY)).thenReturn(List.of());
    when(exec.brokerListOpenOrders(TENANT, STRATEGY)).thenReturn(null);

    TenantDeleteResult result =
        startWith(activities, audit, exec).deleteTenant(TENANT, STRATEGY, BROKER_TARGET, ACTOR);

    assertThat(result.getStatus()).isEqualTo(TenantDeleteResult.Status.BLOCKED);
    assertThat(result.getBlockedBy()).isEqualTo(TenantDeleteResult.BlockReason.BROKER_NOT_FLAT);
    verifyNoInteractions(activities);
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, times(1)).log(captor.capture());
    assertThat(captor.getValue().getSubject().get("detail").toString())
        .contains("null orders")
        .contains("fail-closed");
  }

  @Test
  void p5HasTradeHistory_blocks_zeroTeardown_andAudits() {
    TenantDeleteActivities activities = mock(TenantDeleteActivities.class);
    AuditActivities audit = mock(AuditActivities.class);
    ReconciliationExecActivity exec = mock(ReconciliationExecActivity.class);
    // Broker flat (P4 passes) but the journal has rows → P5 blocks.
    when(exec.brokerListOpenPositions(TENANT, STRATEGY)).thenReturn(List.of());
    when(exec.brokerListOpenOrders(TENANT, STRATEGY)).thenReturn(List.of());
    when(exec.journalCountByTenant(TENANT, STRATEGY)).thenReturn(3L);

    TenantDeleteResult result =
        startWith(activities, audit, exec).deleteTenant(TENANT, STRATEGY, BROKER_TARGET, ACTOR);

    assertThat(result.getStatus()).isEqualTo(TenantDeleteResult.Status.BLOCKED);
    assertThat(result.getBlockedBy()).isEqualTo(TenantDeleteResult.BlockReason.HAS_TRADE_HISTORY);
    assertThat(result.getDeletedConfigRows()).isZero();
    verifyNoInteractions(activities);
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, times(1)).log(captor.capture());
    assertThat(captor.getValue().getSubject()).containsEntry("blocked_by", "HAS_TRADE_HISTORY");
  }

  @Test
  void brokerReadFault_blocks_failClosed_zeroTeardown() {
    TenantDeleteActivities activities = mock(TenantDeleteActivities.class);
    AuditActivities audit = mock(AuditActivities.class);
    ReconciliationExecActivity exec = mock(ReconciliationExecActivity.class);
    // The broker flatness read faults on every (bounded-retry) attempt → the workflow must treat
    // flatness as UNKNOWN and fail closed to BLOCKED, never fall through to teardown.
    when(exec.brokerListOpenPositions(TENANT, STRATEGY))
        .thenThrow(new RuntimeException("broker unreachable"));

    TenantDeleteResult result =
        startWith(activities, audit, exec).deleteTenant(TENANT, STRATEGY, BROKER_TARGET, ACTOR);

    assertThat(result.getStatus()).isEqualTo(TenantDeleteResult.Status.BLOCKED);
    assertThat(result.getBlockedBy()).isEqualTo(TenantDeleteResult.BlockReason.BROKER_NOT_FLAT);
    verifyNoInteractions(activities);
    verify(audit, times(1)).log(any());
  }

  @Test
  void journalReadFault_blocks_failClosed_zeroTeardown() {
    TenantDeleteActivities activities = mock(TenantDeleteActivities.class);
    AuditActivities audit = mock(AuditActivities.class);
    ReconciliationExecActivity exec = mock(ReconciliationExecActivity.class);
    when(exec.brokerListOpenPositions(TENANT, STRATEGY)).thenReturn(List.of());
    when(exec.brokerListOpenOrders(TENANT, STRATEGY)).thenReturn(List.of());
    when(exec.journalCountByTenant(TENANT, STRATEGY))
        .thenThrow(new RuntimeException("journal DB down"));

    TenantDeleteResult result =
        startWith(activities, audit, exec).deleteTenant(TENANT, STRATEGY, BROKER_TARGET, ACTOR);

    assertThat(result.getStatus()).isEqualTo(TenantDeleteResult.Status.BLOCKED);
    assertThat(result.getBlockedBy()).isEqualTo(TenantDeleteResult.BlockReason.HAS_TRADE_HISTORY);
    verifyNoInteractions(activities);
  }

  @Test
  void bothGatesPass_allTeardownStepsIdempotentSuccess_completesWithZero() {
    // Every teardown step yields success as if already gone (the void steps no-op, config delete
    // returns the Mockito-default 0). The workflow still completes cleanly.
    TenantDeleteActivities activities = mock(TenantDeleteActivities.class);
    AuditActivities audit = mock(AuditActivities.class);

    TenantDeleteResult result =
        startWith(activities, audit, flatAndUntraded())
            .deleteTenant(TENANT, STRATEGY, BROKER_TARGET, ACTOR);

    assertThat(result.getStatus()).isEqualTo(TenantDeleteResult.Status.COMPLETED);
    assertThat(result.getDeletedConfigRows()).isZero();
    verify(activities, times(1)).deleteReconSchedules(TENANT, STRATEGY);
    verify(activities, times(1)).terminateKillSwitchWorkflow(TENANT, STRATEGY);
    verify(activities, times(1)).terminateAccountKillSwitchWorkflow(TENANT);
    verify(activities, times(1)).deleteStrategyConfig(eq(TENANT), eq(STRATEGY), eq(ACTOR));
  }
}
