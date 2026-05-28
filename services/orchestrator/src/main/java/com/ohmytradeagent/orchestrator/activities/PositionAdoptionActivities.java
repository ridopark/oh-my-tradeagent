package com.ohmytradeagent.orchestrator.activities;

import io.temporal.activity.ActivityInterface;

/**
 * Issue #239: operator-triggered orphan-position adoption. Reconstructs a {@code PositionWorkflow}
 * owner from broker truth + the journal for a confirmed orphaned broker lot (a broker-held position
 * with no running {@code PositionWorkflow}), so an orphaned-but-legit position can be re-attached
 * and managed normally instead of being force-flattened.
 *
 * <p>Recon detects orphans today but only audits them ({@code ReconciliationWorkflowImpl}
 * PositionOrphan); this Activity is the validated, idempotent recovery path. The recon-loop
 * auto-fire trigger + dry-run mode are deferred follow-ups — this exposes the manual mechanism on
 * its own merits.
 */
@ActivityInterface
public interface PositionAdoptionActivities {

  /**
   * Adopt the confirmed orphan {@code (tenantId, strategyId, occ)}: validate against broker truth,
   * reconstruct the {@code PositionWorkflowInput}, start a {@code PositionWorkflow} owner with the
   * canonical workflow id + search attributes, signal {@code onFill} so the first-fill gate wakes,
   * terminalize the stale journal row, seed the discovery cache, and emit a {@code PositionAdopted}
   * audit with provenance.
   *
   * <p>Idempotent / safe: a no-op ({@link AdoptionResult.Outcome#ALREADY_OWNED}) when a live owner
   * already exists, and a refusal ({@link AdoptionResult.Outcome#REFUSED_NOT_HELD}) when the broker
   * does not actually hold the lot.
   */
  AdoptionResult adoptOrphanPosition(String tenantId, String strategyId, String occ);
}
