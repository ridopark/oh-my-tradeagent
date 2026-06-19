package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.BrokerPosition;
import com.ohmytradeagent.contract.PositionSnapshotRequest;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.List;

/**
 * Short-lived workflow that lists a brokerage account's open positions WITH their live marks and
 * returns them. Started synchronously by the tenant-dashboard BFF (a Temporal <em>client</em>,
 * which cannot dispatch an Activity directly) to back the dashboard's per-position current price +
 * today's / total unrealized P&amp;L — the same start-and-getResult pattern {@link
 * AccountSnapshotWorkflow} uses for the account-equity figure.
 *
 * <p>It runs as a workflow (not a plain Activity) for the same reason {@link
 * AccountSnapshotWorkflow} does: the {@link
 * com.ohmytradeagent.contract.activities.ReconciliationExecActivity#brokerListOpenPositions} read
 * MUST route through the exec task queue ({@code broker-<broker_target>}), and a task-queue-pinned
 * activity stub can only be created inside a workflow ({@code Workflow.newActivityStub}). No broker
 * credentials live in the caller; the marks are account-level broker truth (shared by every tenant
 * routing to a given {@code broker_target}), never a risk-gate input.
 *
 * <p>Returns {@code List<BrokerPosition>} directly — the same stable contract type the underlying
 * activity returns. (A wrapper schema embedding positions would either duplicate the BrokerPosition
 * fields or require cross-file {@code $ref}, which this contract set does not use; a list of an
 * existing contract DTO is already a stable result type.)
 */
@WorkflowInterface
public interface PositionSnapshotWorkflow {

  /**
   * Dispatch {@link
   * com.ohmytradeagent.contract.activities.ReconciliationExecActivity#brokerListOpenPositions} to
   * {@code broker-<request.broker_target>} and return its {@link BrokerPosition} list (marks
   * included when the broker carries them). The {@code tenant_id} / {@code strategy_id} on the
   * request are passed through to the activity as forward-compat filter hooks.
   */
  @WorkflowMethod
  List<BrokerPosition> snapshot(PositionSnapshotRequest request);
}
