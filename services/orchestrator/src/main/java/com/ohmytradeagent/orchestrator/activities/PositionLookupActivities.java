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
   * Seeds the armed-watchlist Redis set ({@link
   * com.ohmytradeagent.contract.identity.WorkflowIds#armedWatchlistCacheKey}) with this leg's
   * workflow id when a {@code WatchlistTriggerWorkflow} arms. The BFF enumerates this set instead
   * of a lagging {@code listExecutions} visibility query. BEST-EFFORT: a Redis failure is swallowed
   * and logged so it can never fail or stall arming (the cache is a hint, never a gate); SADD is
   * idempotent and the key expires after 2 days.
   */
  void cacheArmedLeg(
      String tenantId, String strategyId, java.time.LocalDate etDate, String workflowId);

  /**
   * Issue #165 Phase 3: returns {@code true} iff a Temporal workflow with this id is currently
   * RUNNING. Returns {@code false} on {@code NotFound} (no execution by that id), and for any
   * non-RUNNING terminal status (COMPLETED, FAILED, TERMINATED, CANCELED, TIMED_OUT,
   * CONTINUED_AS_NEW). Used by {@code ReconciliationWorkflow} to detect filled-but-no-workflow
   * orphans without leaning on Visibility (which lags behind the durable history).
   */
  boolean isPositionWorkflowRunning(String workflowId);

  /**
   * Account-scoped (any-strategy) sibling-owner coverage probe for the cross-strategy
   * recon-orphan-suppression fix. Multiple strategies under one tenant route to the SAME broker
   * account, so a broker-held OCC managed by a DIFFERENT strategy's running {@code
   * PositionWorkflow} would otherwise false-page as a {@code PositionOrphan} in this strategy's
   * recon. Returns the summed {@code remainingQty} across every confirmed-RUNNING PositionWorkflow
   * (under ANY strategy of {@code tenantId}) that manages {@code occPadded}.
   *
   * <p>Resolution is cache-driven (Redis SCAN of {@code pos:{tenant}:*:{occPadded}}), confirmed
   * RUNNING per owner via {@link #isPositionWorkflowRunning}, with remaining qty read from each
   * owner's {@code positionState} query. BEST-EFFORT / read-only: any error returns {@code 0L}
   * (zero coverage → recon pages → safe degrade to today's behavior). {@code occPadded} must
   * already be in the padded canonical form (see {@code OccSymbol.padded}).
   */
  long sumRunningOwnerRemainingQtyForOcc(String tenantId, String occPadded);
}
