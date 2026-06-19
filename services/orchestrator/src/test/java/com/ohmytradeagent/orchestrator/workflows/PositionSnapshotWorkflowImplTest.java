package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.BrokerPosition;
import com.ohmytradeagent.contract.PositionSnapshotRequest;
import com.ohmytradeagent.contract.activities.ReconciliationExecActivity;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Coverage for the dashboard live-marks workflow. The broker-truth {@link
 * ReconciliationExecActivity} is registered on a SEPARATE {@code broker-alpaca-paper} worker, so a
 * passing test proves the snapshot path routes the positions read through the exec task queue (the
 * whole reason this is a workflow and not a direct client call). Mirrors {@link
 * AccountSnapshotWorkflowImpl}'s pattern via the {@link AdoptionWorkflowImplTest} two-queue setup.
 */
class PositionSnapshotWorkflowImplTest {

  private static final String CORE_QUEUE = "orchestrator-core";
  private static final String EXEC_QUEUE = "broker-alpaca-paper";

  private static final String TENANT = "dev";
  private static final String STRATEGY = "copytrade-v1";
  private static final String OCC = "SPY260519C00737000";

  private TestWorkflowEnvironment env;
  private ReconciliationExecActivity exec;

  @BeforeEach
  void setUp() {
    env = TestWorkflowEnvironment.newInstance();
    exec = mock(ReconciliationExecActivity.class);

    Worker coreWorker = env.newWorker(CORE_QUEUE);
    coreWorker.registerWorkflowImplementationTypes(PositionSnapshotWorkflowImpl.class);

    // Broker truth lives on a DISTINCT queue — the snapshot path only reaches it by routing through
    // the exec task queue, which is what makes this a workflow rather than a direct client read.
    Worker brokerWorker = env.newWorker(EXEC_QUEUE);
    brokerWorker.registerActivitiesImplementations(exec);

    env.start();
  }

  @AfterEach
  void tearDown() {
    env.close();
  }

  private PositionSnapshotWorkflow newStub() {
    return env.getWorkflowClient()
        .newWorkflowStub(
            PositionSnapshotWorkflow.class,
            WorkflowOptions.newBuilder().setTaskQueue(CORE_QUEUE).build());
  }

  private PositionSnapshotRequest request(PositionSnapshotRequest.BrokerTarget target) {
    PositionSnapshotRequest in = new PositionSnapshotRequest();
    in.setSchemaVersion(1L);
    in.setBrokerTarget(target);
    in.setTenantId(TENANT);
    in.setStrategyId(STRATEGY);
    in.setCorrelationId("dashboard-test");
    return in;
  }

  private BrokerPosition position() {
    BrokerPosition p = new BrokerPosition();
    p.setSchemaVersion(1L);
    p.setOptionSymbol(OCC);
    p.setQty(5L);
    p.setSide(BrokerPosition.Side.LONG);
    p.setAvgEntryPrice(new BigDecimal("0.84"));
    p.setCurrentPrice(new BigDecimal("1.20"));
    p.setUnrealizedPl(new BigDecimal("180.00"));
    p.setUnrealizedIntradayPl(new BigDecimal("-15.00"));
    return p;
  }

  @Test
  void dispatchesBrokerListThroughExecQueue_returnsPositionsWithMarks() {
    when(exec.brokerListOpenPositions(TENANT, STRATEGY)).thenReturn(List.of(position()));

    List<BrokerPosition> result =
        newStub().snapshot(request(PositionSnapshotRequest.BrokerTarget.ALPACA_PAPER));

    assertThat(result).hasSize(1);
    BrokerPosition p = result.get(0);
    assertThat(p.getOptionSymbol()).isEqualTo(OCC);
    assertThat(p.getCurrentPrice()).isEqualByComparingTo(new BigDecimal("1.20"));
    assertThat(p.getUnrealizedPl()).isEqualByComparingTo(new BigDecimal("180.00"));
    assertThat(p.getUnrealizedIntradayPl()).isEqualByComparingTo(new BigDecimal("-15.00"));
    // The forward-compat tenant/strategy hooks reached the activity.
    verify(exec).brokerListOpenPositions(TENANT, STRATEGY);
  }

  @Test
  void emptyBrokerList_returnsEmpty() {
    when(exec.brokerListOpenPositions(TENANT, STRATEGY)).thenReturn(List.of());

    List<BrokerPosition> result =
        newStub().snapshot(request(PositionSnapshotRequest.BrokerTarget.ALPACA_PAPER));

    assertThat(result).isEmpty();
  }

  @Test
  void legacyBareTarget_failsFastWithInvalidBrokerTargetError_noBrokerCall() {
    // The legacy bare "paper" target has no worker queue; taskQueueFor must fail the workflow fast
    // with a non-retryable InvalidBrokerTargetError instead of hanging on a StartToCloseTimeout.
    PositionSnapshotWorkflow wf = newStub();
    PositionSnapshotRequest req = request(PositionSnapshotRequest.BrokerTarget.PAPER);

    assertThatThrownBy(() -> wf.snapshot(req))
        .isInstanceOf(WorkflowFailedException.class)
        .hasCauseInstanceOf(ApplicationFailure.class)
        .satisfies(
            t -> {
              ApplicationFailure af = (ApplicationFailure) t.getCause();
              assertThat(af.getType()).isEqualTo("InvalidBrokerTargetError");
              assertThat(af.isNonRetryable()).isTrue();
            });

    verify(exec, org.mockito.Mockito.never()).brokerListOpenPositions(anyString(), anyString());
  }
}
