package com.ohmytradeagent.orchestrator.activities;

import io.temporal.activity.ActivityInterface;

/**
 * Phase F (operator-account-onboarding): the client-side kill-switch operations the {@code
 * LiveActivationWorkflow} needs but cannot do directly. A workflow cannot use a {@code
 * WorkflowClient} (query/signal another workflow) inside its deterministic body, so these are
 * Activities — the same reason {@code RiskActivitiesImpl} reads the kill-switch state from an
 * Activity rather than the workflow.
 *
 * <p>Both methods are keyed on the per-{@code (tenant, strategy)} {@code KillSwitchWorkflow}
 * ({@code WorkflowIds.killswitch}).
 */
@ActivityInterface
public interface LiveActivationGateActivities {

  /**
   * True iff the per-{@code (tenant, strategy)} {@code KillSwitchWorkflow} is reachable and its
   * state query returns a non-null state (i.e. the kill switch is running and queryable, so a trip
   * is actually armable). Any failure (workflow-not-found, query rejection, null state, no client)
   * returns false — fail CLOSED: an unreachable kill switch must REFUSE activation, never assume
   * the safety net is present.
   */
  boolean killSwitchArmable(String tenantId, String strategyId);

  /**
   * Trip the per-{@code (tenant, strategy)} {@code KillSwitchWorkflow} via its {@code
   * trip_killswitch} Update (the same mechanism api-gateway's {@code POST /killswitch/trip} uses),
   * attributing the trip to {@code operator:<operatorId>}. Used by the deactivation path to ensure
   * no live order can be placed after a one-click deactivation even before the next gate read.
   */
  void tripKillSwitch(String tenantId, String strategyId, String operatorId, String reason);
}
