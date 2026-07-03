package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.orchestrator.activities.TenantDeleteActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/**
 * Operator tenant-delete teardown impl (PLAN-2026-07-03, Phase 2). Runs the three teardown steps in
 * strict order a → b → c; the ordering is load-bearing: step (a) resolves {@code broker_target}
 * from the config row to compute the recon schedule id, so it MUST run BEFORE step (c) deletes that
 * row (otherwise the id is uncomputable and the schedule is orphaned).
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
    // (a) resolve broker_target FIRST, then reap the recon schedule.
    activities.resolveBrokerTargetAndDeleteReconSchedule(tenantId, strategyId);
    // (b) terminate the kill-switch workflow.
    activities.terminateKillSwitchWorkflow(tenantId, strategyId);
    // (c) delete the config row (+ retained TenantDeleted tombstone). LAST, so (a) could resolve
    // broker_target from a still-present row.
    return activities.deleteStrategyConfig(tenantId, strategyId, actor);
  }
}
