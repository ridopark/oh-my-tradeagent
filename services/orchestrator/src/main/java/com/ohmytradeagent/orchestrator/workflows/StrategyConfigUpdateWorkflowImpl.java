package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.StrategyConfigUpdateRequest;
import com.ohmytradeagent.contract.StrategyConfigUpdateResult;
import com.ohmytradeagent.orchestrator.activities.StrategyConfigUpdateActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/**
 * Single-step config-write workflow: dispatches {@code update} and returns its result. No state, no
 * timers, no {@code Workflow.getVersion} change-point (net-new workflow type), and no {@code
 * Instant.now}/{@code UUID}/non-deterministic iteration in the body — so replay determinism is
 * trivially preserved.
 *
 * <p>The activity stub inherits this workflow's task queue (orchestrator-core) — the
 * StrategyConfigWriter is an in-process {@code @Component}, so this NEVER routes to a broker-*
 * queue. Retry is BOUNDED ({@code maximumAttempts=3}), NOT unlimited: the underlying write is a
 * compare-and-set (not an idempotent append), so an unbounded retry on a flapping fault could
 * thrash the CAS; three attempts cover a transient corrupt-row/DB blip and then surface the fault.
 * The coarse rejection outcomes are returned as a normal result (not thrown), so they do not
 * consume a retry attempt — only a propagated IllegalStateException does.
 */
public class StrategyConfigUpdateWorkflowImpl implements StrategyConfigUpdateWorkflow {

  private final StrategyConfigUpdateActivities activity =
      Workflow.newActivityStub(
          StrategyConfigUpdateActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofSeconds(30))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build());

  @Override
  public StrategyConfigUpdateResult update(StrategyConfigUpdateRequest request) {
    return activity.update(request);
  }
}
