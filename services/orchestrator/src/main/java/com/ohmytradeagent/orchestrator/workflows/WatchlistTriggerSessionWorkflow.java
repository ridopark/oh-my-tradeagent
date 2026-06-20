package com.ohmytradeagent.orchestrator.workflows;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Parent watchlist-trigger session: for one (tenant, strategy, et_date) it parses the daily
 * watchlist, arms one child {@link WatchlistTriggerWorkflow} per qualifying leg (fan-out), and at
 * market close cancels every un-fired child (EOD sweep). One session per trading day.
 */
@WorkflowInterface
public interface WatchlistTriggerSessionWorkflow {

  /** Returns a short summary string {@code armed=<n>;skipped=<n>;eod=<bool>} for audit/testing. */
  @WorkflowMethod
  String run(WatchlistTriggerSessionWorkflowInput input);
}
