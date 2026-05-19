package com.ohmytradeagent.orchestrator.activities;

import io.temporal.activity.ActivityInterface;

/**
 * Resolves OCC option symbols to the currently-running {@link
 * com.ohmytradeagent.orchestrator.workflows.PositionWorkflow} workflow_id for a given (tenant,
 * strategy). Hot path is Redis-cached; cache miss falls back to Temporal Visibility query keyed on
 * {@code TenantStrategy + ContractSymbol + ExecutionStatus + WorkflowType}.
 */
@ActivityInterface
public interface PositionLookupActivities {

  /**
   * @return the matching PositionWorkflow workflow_id, or {@code null} when no open position exists
   *     for the OCC under this tenant/strategy.
   */
  String findPositionWorkflowId(String tenantId, String strategyId, String occ);

  /** Write-through cache hook called by CopytradeSignalWorkflow once a PositionWorkflow starts. */
  void cachePositionMapping(String tenantId, String strategyId, String occ, String workflowId);

  /**
   * Issue #165 Phase 3: returns {@code true} iff a Temporal workflow with this id is currently
   * RUNNING. Returns {@code false} on {@code NotFound} (no execution by that id), and for any
   * non-RUNNING terminal status (COMPLETED, FAILED, TERMINATED, CANCELED, TIMED_OUT,
   * CONTINUED_AS_NEW). Used by {@code ReconciliationWorkflow} to detect filled-but-no-workflow
   * orphans without leaning on Visibility (which lags behind the durable history).
   */
  boolean isPositionWorkflowRunning(String workflowId);
}
