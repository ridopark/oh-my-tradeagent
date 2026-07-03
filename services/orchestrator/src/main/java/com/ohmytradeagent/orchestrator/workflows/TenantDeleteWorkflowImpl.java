package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.orchestrator.activities.TenantDeleteActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/**
 * Operator tenant-delete teardown impl (PLAN-2026-07-03, Phase 2). Runs the three teardown steps a
 * → b → c. The order is NOT load-bearing: step (a) reaps recon schedules by the {@code (tenant,
 * strategy)} prefix and never reads {@code broker_target} from the config row, so it has no
 * dependency on step (c) still finding that row. (a → c order is kept only for readability.)
 *
 * <p>All Activities run on this workflow's task queue (orchestrator-core) — every teardown target
 * is orchestrator-owned (the recon schedule client, the kill-switch workflow, the in-process {@code
 * StrategyConfigWriter}), so this NEVER routes to a broker-* queue. Retry is BOUNDED ({@code
 * maximumAttempts=3}): each step is idempotent, so a retry after a transient blip re-converges, and
 * a persistent fault surfaces after three attempts rather than retrying forever.
 *
 * <p>No {@code Workflow.getVersion} change-point (net-new workflow type) and no {@code
 * Instant.now}/{@code UUID}/non-deterministic iteration in the body — replay determinism is
 * trivially preserved.
 */
public class TenantDeleteWorkflowImpl implements TenantDeleteWorkflow {

  private final TenantDeleteActivities activities =
      Workflow.newActivityStub(
          TenantDeleteActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofSeconds(30))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build());

  @Override
  public int deleteTenant(String tenantId, String strategyId, String actor) {
    // (a) reap every recon schedule under the (tenant, strategy) prefix.
    activities.deleteReconSchedules(tenantId, strategyId);
    // (b) terminate the kill-switch workflow.
    activities.terminateKillSwitchWorkflow(tenantId, strategyId);
    // (c) delete the config row (+ retained TenantDeleted tombstone).
    return activities.deleteStrategyConfig(tenantId, strategyId, actor);
  }
}
