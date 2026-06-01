package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.AccountSnapshotRequest;
import com.ohmytradeagent.contract.AccountSnapshotResult;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Short-lived workflow that reads a brokerage account's net-liquidation equity and returns it.
 * Started synchronously by the tenant-dashboard BFF (a Temporal <em>client</em>, which cannot
 * dispatch an Activity directly) to back the dashboard portfolio's account-equity figure — the same
 * start-and-getResult pattern api-gateway uses for {@link AdoptionWorkflow}.
 *
 * <p>It runs as a workflow (not a plain Activity) for the same reason {@link AdoptionWorkflow}
 * does: the {@link com.ohmytradeagent.contract.activities.AccountSnapshotActivity} MUST route
 * through the exec task queue ({@code broker-<broker_target>}), and a task-queue-pinned activity
 * stub can only be created inside a workflow ({@code Workflow.newActivityStub}). No broker
 * credentials live in the caller; equity is account-level (shared by every tenant routing to a
 * given {@code broker_target}).
 */
@WorkflowInterface
public interface AccountSnapshotWorkflow {

  /**
   * Dispatch the {@link com.ohmytradeagent.contract.activities.AccountSnapshotActivity} to {@code
   * broker-<request.broker_target>} and return its {@link AccountSnapshotResult}. The request is
   * keyed solely on {@code broker_target} (+ an optional correlation id) — equity is
   * tenant/strategy-independent.
   */
  @WorkflowMethod
  AccountSnapshotResult snapshot(AccountSnapshotRequest request);
}
