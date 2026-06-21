package com.ohmytradeagent.orchestrator.activities;

import io.temporal.activity.ActivityInterface;

/**
 * Phase 6: account-scoped cascade fan-out for {@code AccountKillSwitchWorkflow.trip}. Runs as an
 * Activity (NOT workflow code) because {@code listWorkflowExecutions} is non-deterministic.
 *
 * <p>Unlike the per-strategy {@link KillSwitchCascadeActivities} (which keys on a single {@code
 * TenantStrategy} equality), this signals {@code riskBreach} to EVERY running PositionWorkflow
 * across ALL of the tenant's strategies — the #323 tenant-wide union.
 */
@ActivityInterface
public interface AccountKillSwitchCascadeActivities {

  /**
   * Resolve every strategy of {@code tenantId}, run the proven {@code
   * TenantStrategy='t-<t>/s-<sid>' AND WorkflowType='PositionWorkflow' AND
   * ExecutionStatus='Running'} equality query once per strategy (never a prefix/{@code STARTS_WITH}
   * query — Temporal SQL Visibility lacks it), dedupe by workflow id, and signal each with {@code
   * riskBreach({reason, actor, occurred_at})}. Excludes {@code excludeWorkflowId} (the account
   * kill-switch workflow itself).
   *
   * @return number of signals successfully sent (best-effort; receivers may already be closed).
   */
  long cascadeAccountRiskBreach(
      String tenantId, String excludeWorkflowId, String reason, String actor);
}
