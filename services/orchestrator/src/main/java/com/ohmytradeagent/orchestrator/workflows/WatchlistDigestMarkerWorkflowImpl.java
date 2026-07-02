package com.ohmytradeagent.orchestrator.workflows;

/**
 * Empty-body dedup marker (see {@link WatchlistDigestMarkerWorkflow}). Registered on the
 * orchestrator-core worker so the {@code REJECT_DUPLICATE} start has a runnable type (an
 * unregistered type would create a stuck, forever-retrying execution). The body does nothing and
 * completes immediately — no {@code Instant.now}/{@code UUID}/non-deterministic iteration, so it is
 * trivially replay-deterministic.
 */
public class WatchlistDigestMarkerWorkflowImpl implements WatchlistDigestMarkerWorkflow {

  @Override
  public void mark(String tenantId, String etDate) {
    // Intentionally empty: this workflow exists only as a per-(tenant, etDate) dedup token.
  }
}
