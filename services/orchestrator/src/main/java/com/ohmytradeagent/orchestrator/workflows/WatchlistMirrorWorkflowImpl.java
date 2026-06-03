package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.WatchlistMirrorPayload;
import com.ohmytradeagent.orchestrator.activities.WatchlistMirrorActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/**
 * Single-step watchlist mirror: dispatches {@code postWatchlistAlert} (which formats and posts to
 * the trade-alert Discord webhook) and completes. No formatting happens here — keeping the workflow
 * body free of {@code Instant.now}/{@code UUID}/non-deterministic iteration preserves replay
 * determinism. Net-new workflow type, so no {@code Workflow.getVersion} change-point is needed.
 */
public class WatchlistMirrorWorkflowImpl implements WatchlistMirrorWorkflow {

  private final WatchlistMirrorActivities alert =
      Workflow.newActivityStub(
          WatchlistMirrorActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofSeconds(10))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build());

  @Override
  public void mirror(WatchlistMirrorPayload payload) {
    alert.postWatchlistAlert(payload);
  }
}
