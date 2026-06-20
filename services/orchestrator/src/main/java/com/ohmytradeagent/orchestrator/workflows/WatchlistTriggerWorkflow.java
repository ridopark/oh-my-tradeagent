package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.EquityTick;
import com.ohmytradeagent.contract.FillSignalPayload;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Child workflow for a single watchlist-trigger leg: it arms one entry, runs the {@link
 * com.ohmytradeagent.orchestrator.domain.EntryStateMachine} over the streaming equity feed, and
 * fires at most once. Un-fired legs are cancelled at market close.
 */
@WorkflowInterface
public interface WatchlistTriggerWorkflow {

  @WorkflowMethod
  String run(WatchlistTriggerWorkflowInput input);

  /** Pushed by {@code SubscribeEquityActivity} on each (throttled, min-move) equity tick. */
  @SignalMethod
  void equityTick(EquityTick tick);

  /** Operator/parent request to cancel the un-fired leg before EOD. */
  @SignalMethod
  void cancel();

  /** Broker fill for the placed entry order (delivered by the fill listener / exec sidecar). */
  @SignalMethod
  void onFill(FillSignalPayload event);
}
