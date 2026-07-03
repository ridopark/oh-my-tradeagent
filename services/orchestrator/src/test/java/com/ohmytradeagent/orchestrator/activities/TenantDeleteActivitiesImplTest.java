package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.platform.StrategyConfigWriter;
import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowStub;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleHandle;
import io.temporal.serviceclient.WorkflowServiceStubs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 (PLAN-2026-07-03-operator-tenant-delete) unit coverage for the teardown Activities'
 * idempotency and id grammar. The {@code scheduleClient()} factory is overridden so no Temporal
 * server is needed; the recon schedule id + kill-switch workflow id are asserted against the SAME
 * grammar {@code ReconciliationScheduleBootstrapper} / {@code WorkflowIds} produce.
 */
class TenantDeleteActivitiesImplTest {

  private static final String TENANT = "dev";
  private static final String STRATEGY = "copytrade-v1";
  private static final String ACTOR = "operator:ridopark";
  private static final String SCHEDULE_ID = "recon-v2-t-dev-s-copytrade-v1-alpaca-paper";
  private static final String KILLSWITCH_ID = "t-dev/s-copytrade-v1/killswitch";

  private StrategyRegistry registry;
  private StrategyConfigWriter writer;
  private WorkflowClient workflowClient;
  private WorkflowServiceStubs serviceStubs;
  private ScheduleClient scheduleClient;
  private TenantDeleteActivitiesImpl impl;

  @BeforeEach
  void setUp() {
    registry = mock(StrategyRegistry.class);
    writer = mock(StrategyConfigWriter.class);
    workflowClient = mock(WorkflowClient.class);
    serviceStubs = mock(WorkflowServiceStubs.class);
    scheduleClient = mock(ScheduleClient.class);
    impl =
        new TenantDeleteActivitiesImpl(registry, writer, workflowClient, serviceStubs) {
          @Override
          ScheduleClient scheduleClient() {
            return scheduleClient;
          }
        };
  }

  private static StrategyConfig paperConfig() {
    StrategyConfig c = new StrategyConfig();
    c.setBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_PAPER);
    return c;
  }

  // ---- step (a): resolve broker_target + delete recon schedule ------------------------------

  @Test
  void resolve_deletesScheduleWithBootstrapperGrammarId() {
    when(registry.get(TENANT, STRATEGY)).thenReturn(paperConfig());
    ScheduleHandle handle = mock(ScheduleHandle.class);
    when(scheduleClient.getHandle(SCHEDULE_ID)).thenReturn(handle);

    impl.resolveBrokerTargetAndDeleteReconSchedule(TENANT, STRATEGY);

    verify(scheduleClient).getHandle(SCHEDULE_ID);
    verify(handle).delete();
  }

  @Test
  void resolve_scheduleAbsent_swallowsNotFound() {
    when(registry.get(TENANT, STRATEGY)).thenReturn(paperConfig());
    ScheduleHandle handle = mock(ScheduleHandle.class);
    when(scheduleClient.getHandle(SCHEDULE_ID)).thenReturn(handle);
    doThrow(new StatusRuntimeException(Status.NOT_FOUND)).when(handle).delete();

    assertThatCode(() -> impl.resolveBrokerTargetAndDeleteReconSchedule(TENANT, STRATEGY))
        .doesNotThrowAnyException();
  }

  @Test
  void resolve_scheduleDeleteOtherError_rethrows() {
    when(registry.get(TENANT, STRATEGY)).thenReturn(paperConfig());
    ScheduleHandle handle = mock(ScheduleHandle.class);
    when(scheduleClient.getHandle(SCHEDULE_ID)).thenReturn(handle);
    doThrow(new RuntimeException("temporal frontend down")).when(handle).delete();

    assertThatThrownBy(() -> impl.resolveBrokerTargetAndDeleteReconSchedule(TENANT, STRATEGY))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("temporal frontend down");
  }

  @Test
  void resolve_configAbsent_noScheduleDelete() {
    // A prior teardown already deleted the config row; broker_target is uncomputable, but the
    // schedule was reaped with it — so this is an idempotent no-op, NOT a fault.
    when(registry.get(TENANT, STRATEGY))
        .thenThrow(new RuntimeException("strategy config not found"));

    assertThatCode(() -> impl.resolveBrokerTargetAndDeleteReconSchedule(TENANT, STRATEGY))
        .doesNotThrowAnyException();
    verifyNoInteractions(scheduleClient);
  }

  @Test
  void resolve_nullBrokerTarget_noScheduleDelete() {
    StrategyConfig c = new StrategyConfig();
    c.setBrokerTarget(null);
    when(registry.get(TENANT, STRATEGY)).thenReturn(c);

    assertThatCode(() -> impl.resolveBrokerTargetAndDeleteReconSchedule(TENANT, STRATEGY))
        .doesNotThrowAnyException();
    verifyNoInteractions(scheduleClient);
  }

  // ---- step (b): terminate kill-switch workflow ---------------------------------------------

  @Test
  void terminate_terminatesKillswitchWorkflowById() {
    WorkflowStub stub = mock(WorkflowStub.class);
    when(workflowClient.newUntypedWorkflowStub(KILLSWITCH_ID)).thenReturn(stub);

    impl.terminateKillSwitchWorkflow(TENANT, STRATEGY);

    // Id grammar must match WorkflowIds.killswitch (the single source of truth the bootstrapper
    // starts the kill switch under).
    assertThat(KILLSWITCH_ID).isEqualTo(WorkflowIds.killswitch(TENANT, STRATEGY));
    verify(workflowClient).newUntypedWorkflowStub(KILLSWITCH_ID);
    verify(stub).terminate(anyString());
  }

  @Test
  void terminate_workflowNotFound_swallows() {
    WorkflowStub stub = mock(WorkflowStub.class);
    when(workflowClient.newUntypedWorkflowStub(KILLSWITCH_ID)).thenReturn(stub);
    doThrow(
            new WorkflowNotFoundException(
                WorkflowExecution.newBuilder().setWorkflowId(KILLSWITCH_ID).build(),
                "KillSwitchWorkflow",
                new RuntimeException("execution not found")))
        .when(stub)
        .terminate(anyString());

    assertThatCode(() -> impl.terminateKillSwitchWorkflow(TENANT, STRATEGY))
        .doesNotThrowAnyException();
  }

  @Test
  void terminate_notFoundStatus_swallows() {
    WorkflowStub stub = mock(WorkflowStub.class);
    when(workflowClient.newUntypedWorkflowStub(KILLSWITCH_ID)).thenReturn(stub);
    doThrow(new StatusRuntimeException(Status.NOT_FOUND)).when(stub).terminate(anyString());

    assertThatCode(() -> impl.terminateKillSwitchWorkflow(TENANT, STRATEGY))
        .doesNotThrowAnyException();
  }

  @Test
  void terminate_otherError_rethrows() {
    WorkflowStub stub = mock(WorkflowStub.class);
    when(workflowClient.newUntypedWorkflowStub(KILLSWITCH_ID)).thenReturn(stub);
    doThrow(new RuntimeException("temporal frontend down")).when(stub).terminate(anyString());

    assertThatThrownBy(() -> impl.terminateKillSwitchWorkflow(TENANT, STRATEGY))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("temporal frontend down");
  }

  // ---- step (c): delete strategy_config -----------------------------------------------------

  @Test
  void deleteStrategyConfig_delegatesToWriter_returnsCount() {
    when(writer.delete(TENANT, STRATEGY, ACTOR)).thenReturn(1);

    int count = impl.deleteStrategyConfig(TENANT, STRATEGY, ACTOR);

    assertThat(count).isEqualTo(1);
    verify(writer).delete(TENANT, STRATEGY, ACTOR);
  }

  @Test
  void deleteStrategyConfig_absent_returnsZero() {
    when(writer.delete(TENANT, STRATEGY, ACTOR)).thenReturn(0);

    assertThat(impl.deleteStrategyConfig(TENANT, STRATEGY, ACTOR)).isZero();
  }
}
