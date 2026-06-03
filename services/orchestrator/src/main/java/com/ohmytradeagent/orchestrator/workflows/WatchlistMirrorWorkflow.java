package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.WatchlistMirrorPayload;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Mirrors the verbatim daily watchlist message to the trade-alert Discord webhook. Single-step: it
 * dispatches one activity and completes. The Temporal workflow TYPE name is the interface simple
 * name {@code WatchlistMirrorWorkflow}, which the signal-source-discord sidecar emits verbatim.
 */
@WorkflowInterface
public interface WatchlistMirrorWorkflow {

  @WorkflowMethod
  void mirror(WatchlistMirrorPayload payload);
}
