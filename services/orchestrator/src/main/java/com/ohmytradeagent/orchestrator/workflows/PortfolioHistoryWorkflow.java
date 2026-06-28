package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.PortfolioHistoryRequest;
import com.ohmytradeagent.contract.PortfolioHistoryResult;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Short-lived workflow that reads a brokerage account's portfolio-history series and returns it.
 * Started synchronously by the tenant-dashboard BFF (a Temporal <em>client</em>, which cannot
 * dispatch an Activity directly) to back the dashboard {@code /live} equity chart — the same
 * start-and-getResult pattern as {@link AccountSnapshotWorkflow}.
 *
 * <p>It runs as a workflow (not a plain Activity) for the same reason {@link
 * AccountSnapshotWorkflow} does: the {@link
 * com.ohmytradeagent.contract.activities.PortfolioHistoryActivity} MUST route through the exec task
 * queue ({@code broker-<broker_target>}), and a task-queue-pinned activity stub can only be created
 * inside a workflow ({@code Workflow.newActivityStub}). No broker credentials live in the caller;
 * the history is account-level (shared by every tenant routing to a given {@code broker_target}).
 * READ-ONLY: it places no orders.
 */
@WorkflowInterface
public interface PortfolioHistoryWorkflow {

  /**
   * Dispatch the {@link com.ohmytradeagent.contract.activities.PortfolioHistoryActivity} to {@code
   * broker-<request.broker_target>} and return its {@link PortfolioHistoryResult}. The {@code
   * period}/{@code timeframe} on the request are already resolved by the BFF client — the workflow
   * is a dumb, deterministic pass-through (no clock/random reads).
   */
  @WorkflowMethod
  PortfolioHistoryResult history(PortfolioHistoryRequest request);
}
