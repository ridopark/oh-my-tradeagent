package com.ohmytradeagent.orchestrator.workflows;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Pure per-{@code (tenant, etDate)} dedup token for the daily watchlist digest.
 *
 * <p>The watchlist mirror fans out one {@code WatchlistMirrorWorkflow} per {@code (tenant,
 * strategy)}, so {@code WatchlistMirrorActivitiesImpl.postWatchlistAlert} runs once per strategy.
 * To post the tenant's digest exactly ONCE per day, that activity starts this workflow with id
 * {@code t-{tenant}/wl/{etDate}/digest} under {@code REJECT_DUPLICATE}: the entry whose start
 * succeeds owns the post; every other entry sees {@code WorkflowExecutionAlreadyStarted} and skips
 * the post (but still runs its session start). This type holds no state and does no work — the
 * digest post happens in the activity, gated on the start succeeding — so its {@link #mark} body is
 * intentionally empty.
 */
@WorkflowInterface
public interface WatchlistDigestMarkerWorkflow {

  /**
   * No-op dedup marker. Takes no arguments and does nothing — the dedup key is entirely the
   * workflow id ({@code t-{tenant}/wl/{etDate}/digest}), which is already visible in Temporal.
   */
  @WorkflowMethod
  void mark();
}
