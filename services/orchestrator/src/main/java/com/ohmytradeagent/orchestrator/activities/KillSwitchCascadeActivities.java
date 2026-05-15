package com.ohmytradeagent.orchestrator.activities;

import io.temporal.activity.ActivityInterface;

/**
 * Cascade fan-out side-effect for KillSwitchWorkflow.trip. Runs as an Activity (NOT in workflow
 * code) because {@code listWorkflowExecutions} is non-deterministic and forbidden inside workflows.
 */
@ActivityInterface
public interface KillSwitchCascadeActivities {

  /**
   * Look up every Running workflow under {@code TenantStrategy=t-<tenant>/s-<strategy>} and signal
   * each with {@code riskBreach({reason, actor, occurred_at})}. Excludes the kill-switch workflow
   * itself ({@code excludeWorkflowId}).
   *
   * @return number of signals successfully sent (best-effort; receivers may already be closed).
   */
  long cascadeRiskBreach(
      String tenantId, String strategyId, String excludeWorkflowId, String reason, String actor);
}
