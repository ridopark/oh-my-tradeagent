package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.StrategyConfigCreateRequest;
import com.ohmytradeagent.contract.StrategyConfigCreateResult;
import com.ohmytradeagent.orchestrator.activities.StrategyConfigCreateActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/**
 * Single-step create-tenant workflow: dispatches {@code create} and returns its result. No state,
 * no timers, no {@code Workflow.getVersion} change-point (net-new workflow type), and no {@code
 * Instant.now}/{@code UUID}/non-deterministic iteration in the body — so replay determinism is
 * trivially preserved.
 *
 * <p>The activity stub inherits this workflow's task queue (orchestrator-core) — the
 * StrategyConfigWriter is an in-process {@code @Component}, so this NEVER routes to a broker-*
 * queue. Retry is BOUNDED ({@code maximumAttempts=3}), NOT unlimited: the underlying write is an
 * INSERT guarded by {@code ON CONFLICT DO NOTHING} (a retry of a committed INSERT yields
 * ALREADY_EXISTS, not a duplicate row), but three attempts cover a transient DB blip and then
 * surface the fault. The coarse outcomes are returned as a normal result (not thrown), so they do
 * not consume a retry attempt — only a propagated IllegalStateException does.
 */
public class StrategyConfigCreateWorkflowImpl implements StrategyConfigCreateWorkflow {

  private final StrategyConfigCreateActivities activity =
      Workflow.newActivityStub(
          StrategyConfigCreateActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofSeconds(30))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build());

  @Override
  public StrategyConfigCreateResult create(StrategyConfigCreateRequest request) {
    return activity.create(request);
  }
}
