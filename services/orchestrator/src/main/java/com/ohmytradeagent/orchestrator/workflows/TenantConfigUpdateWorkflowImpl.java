package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.TenantConfigUpdateRequest;
import com.ohmytradeagent.contract.TenantConfigUpdateResult;
import com.ohmytradeagent.orchestrator.activities.TenantConfigUpdateActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/**
 * Single-step account-cap-write workflow: dispatches {@code update} and returns its result. No
 * state, no timers, no {@code Workflow.getVersion} change-point (NET-NEW workflow type — no
 * existing history to break), and no {@code Instant.now}/{@code UUID}/non-deterministic iteration
 * in the body — so replay determinism is trivially preserved (identical rationale to {@code
 * StrategyConfigUpdateWorkflowImpl}).
 *
 * <p>The activity stub inherits this workflow's task queue (orchestrator-core) — the
 * TenantConfigWriter is an in-process {@code @Component}, so this NEVER routes to a broker-* queue.
 * Retry is BOUNDED ({@code maximumAttempts=3}), NOT unlimited: the underlying write is a
 * compare-and-set (not an idempotent append), so an unbounded retry on a flapping fault could
 * thrash the CAS. The coarse rejection outcomes are returned as a normal result (not thrown), so
 * they do not consume a retry attempt — only a propagated IllegalStateException does.
 */
public class TenantConfigUpdateWorkflowImpl implements TenantConfigUpdateWorkflow {

  private final TenantConfigUpdateActivities activity =
      Workflow.newActivityStub(
          TenantConfigUpdateActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofSeconds(30))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build());

  @Override
  public TenantConfigUpdateResult update(TenantConfigUpdateRequest request) {
    return activity.update(request);
  }
}
