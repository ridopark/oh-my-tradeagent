package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.platform.StrategyConfigWriter;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowStub;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleHandle;
import io.temporal.client.schedules.ScheduleListDescription;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 (PLAN-2026-07-03-operator-tenant-delete) unit coverage for the teardown Activities'
 * idempotency and id grammar. The {@code scheduleClient()} factory is overridden so no Temporal
 * server is needed; the recon schedule reap uses the SAME {@code
 * ReconciliationScheduleBootstrapper.reconSchedulePrefix} match key the boot-time reap uses, and
 * the kill-switch workflow id is asserted against the SAME {@code WorkflowIds} grammar.
 */
class TenantDeleteActivitiesImplTest {

  private static final String TENANT = "dev";
  private static final String STRATEGY = "copytrade-v1";
  private static final String ACTOR = "operator:ridopark";
  // The (tenant, strategy) prefix; every recon schedule id under it is reaped regardless of the
  // trailing broker_target suffix.
  private static final String CURRENT_ID = "recon-v2-t-dev-s-copytrade-v1-alpaca-paper";
  private static final String STALE_ID = "recon-v2-t-dev-s-copytrade-v1-alpaca-live";
  // Same tenant, DIFFERENT strategy — a different prefix, must NOT be reaped.
  private static final String UNRELATED_ID = "recon-v2-t-dev-s-other-v1-alpaca-paper";
  private static final String KILLSWITCH_ID = "t-dev/s-copytrade-v1/killswitch";
  private static final String ACCOUNT_KILLSWITCH_ID = "t-dev/account/killswitch";

  private StrategyConfigWriter writer;
  private WorkflowClient workflowClient;
  private WorkflowServiceStubs serviceStubs;
  private ScheduleClient scheduleClient;
  private TenantDeleteActivitiesImpl impl;

  @BeforeEach
  void setUp() {
    writer = mock(StrategyConfigWriter.class);
    workflowClient = mock(WorkflowClient.class);
    serviceStubs = mock(WorkflowServiceStubs.class);
    scheduleClient = mock(ScheduleClient.class);
    impl =
        new TenantDeleteActivitiesImpl(writer, workflowClient, serviceStubs) {
          @Override
          ScheduleClient scheduleClient() {
            return scheduleClient;
          }
        };
  }

  private static ScheduleListDescription desc(String id) {
    ScheduleListDescription d = mock(ScheduleListDescription.class);
    when(d.getScheduleId()).thenReturn(id);
    return d;
  }

  // ---- step (a): reap ALL recon schedules under the (tenant, strategy) prefix ----------------

  @Test
  void reap_deletesAllUnderPrefix_includingDifferentBrokerSuffix_notUnrelated() {
    // The current-broker id, a stale-broker id under the SAME (tenant,strategy) prefix, and an
    // UNRELATED id under a different strategy prefix. Both prefix matches are reaped; the unrelated
    // one is untouched — this proves the different-broker-suffix zombie (problem A-1) is reaped.
    // Build the descriptions (each self-stubs getScheduleId) BEFORE the outer listSchedules stub —
    // nesting a when() inside the outer when()'s argument trips Mockito's unfinished-stubbing
    // guard.
    ScheduleListDescription current = desc(CURRENT_ID);
    ScheduleListDescription stale = desc(STALE_ID);
    ScheduleListDescription unrelated = desc(UNRELATED_ID);
    when(scheduleClient.listSchedules()).thenReturn(Stream.of(current, stale, unrelated));
    ScheduleHandle currentHandle = mock(ScheduleHandle.class);
    ScheduleHandle staleHandle = mock(ScheduleHandle.class);
    when(scheduleClient.getHandle(CURRENT_ID)).thenReturn(currentHandle);
    when(scheduleClient.getHandle(STALE_ID)).thenReturn(staleHandle);

    impl.deleteReconSchedules(TENANT, STRATEGY);

    verify(scheduleClient).getHandle(CURRENT_ID);
    verify(currentHandle).delete();
    verify(scheduleClient).getHandle(STALE_ID);
    verify(staleHandle).delete();
    verify(scheduleClient, never()).getHandle(UNRELATED_ID);
  }

  @Test
  void reap_emptyList_noDelete() {
    when(scheduleClient.listSchedules()).thenReturn(Stream.empty());

    assertThatCode(() -> impl.deleteReconSchedules(TENANT, STRATEGY)).doesNotThrowAnyException();
    verify(scheduleClient, never()).getHandle(anyString());
  }

  @Test
  void reap_perDeleteNotFound_swallowedAndContinues() {
    // First match is already gone (a peer race or a prior partial run); the reap must swallow it
    // and
    // still reap the second match — idempotency across the loop.
    ScheduleListDescription current = desc(CURRENT_ID);
    ScheduleListDescription stale = desc(STALE_ID);
    when(scheduleClient.listSchedules()).thenReturn(Stream.of(current, stale));
    ScheduleHandle goneHandle = mock(ScheduleHandle.class);
    ScheduleHandle staleHandle = mock(ScheduleHandle.class);
    when(scheduleClient.getHandle(CURRENT_ID)).thenReturn(goneHandle);
    when(scheduleClient.getHandle(STALE_ID)).thenReturn(staleHandle);
    doThrow(new StatusRuntimeException(Status.NOT_FOUND)).when(goneHandle).delete();

    assertThatCode(() -> impl.deleteReconSchedules(TENANT, STRATEGY)).doesNotThrowAnyException();
    verify(goneHandle).delete();
    verify(staleHandle).delete();
  }

  @Test
  void reap_perDeleteGenuineError_propagates() {
    // Regression guard for the HIGH bug: a genuine (non-not-found) delete failure MUST propagate so
    // the bounded activity retry fires — it must NOT be swallowed as false success.
    ScheduleListDescription current = desc(CURRENT_ID);
    when(scheduleClient.listSchedules()).thenReturn(Stream.of(current));
    ScheduleHandle handle = mock(ScheduleHandle.class);
    when(scheduleClient.getHandle(CURRENT_ID)).thenReturn(handle);
    doThrow(new StatusRuntimeException(Status.INTERNAL)).when(handle).delete();

    assertThatThrownBy(() -> impl.deleteReconSchedules(TENANT, STRATEGY))
        .isInstanceOf(StatusRuntimeException.class);
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

  // ---- step (b'): terminate account-level kill-switch workflow ------------------------------

  @Test
  void terminateAccount_terminatesAccountKillswitchWorkflowById() {
    WorkflowStub stub = mock(WorkflowStub.class);
    when(workflowClient.newUntypedWorkflowStub(ACCOUNT_KILLSWITCH_ID)).thenReturn(stub);

    impl.terminateAccountKillSwitchWorkflow(TENANT);

    // Id grammar must match WorkflowIds.accountKillswitch (the single source of truth the
    // KillSwitchBootstrapper starts the account switch under) — no s- segment (tenant-scoped).
    assertThat(ACCOUNT_KILLSWITCH_ID).isEqualTo(WorkflowIds.accountKillswitch(TENANT));
    verify(workflowClient).newUntypedWorkflowStub(ACCOUNT_KILLSWITCH_ID);
    verify(stub).terminate(anyString());
  }

  @Test
  void terminateAccount_workflowNotFound_swallows() {
    // The staging-paper-2 orphan scenario in reverse: an absent account switch (already
    // terminated/never started) must be idempotent success, not a fault.
    WorkflowStub stub = mock(WorkflowStub.class);
    when(workflowClient.newUntypedWorkflowStub(ACCOUNT_KILLSWITCH_ID)).thenReturn(stub);
    doThrow(
            new WorkflowNotFoundException(
                WorkflowExecution.newBuilder().setWorkflowId(ACCOUNT_KILLSWITCH_ID).build(),
                "AccountKillSwitchWorkflow",
                new RuntimeException("execution not found")))
        .when(stub)
        .terminate(anyString());

    assertThatCode(() -> impl.terminateAccountKillSwitchWorkflow(TENANT))
        .doesNotThrowAnyException();
  }

  @Test
  void terminateAccount_notFoundStatus_swallows() {
    WorkflowStub stub = mock(WorkflowStub.class);
    when(workflowClient.newUntypedWorkflowStub(ACCOUNT_KILLSWITCH_ID)).thenReturn(stub);
    doThrow(new StatusRuntimeException(Status.NOT_FOUND)).when(stub).terminate(anyString());

    assertThatCode(() -> impl.terminateAccountKillSwitchWorkflow(TENANT))
        .doesNotThrowAnyException();
  }

  @Test
  void terminateAccount_otherError_rethrows() {
    WorkflowStub stub = mock(WorkflowStub.class);
    when(workflowClient.newUntypedWorkflowStub(ACCOUNT_KILLSWITCH_ID)).thenReturn(stub);
    doThrow(new RuntimeException("temporal frontend down")).when(stub).terminate(anyString());

    assertThatThrownBy(() -> impl.terminateAccountKillSwitchWorkflow(TENANT))
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
