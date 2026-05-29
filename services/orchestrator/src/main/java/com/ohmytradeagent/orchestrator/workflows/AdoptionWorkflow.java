package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.AdoptionResult;
import com.ohmytradeagent.contract.AdoptionWorkflowInput;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Issue #239/#285: short-lived, operator-triggered orphan-position adoption workflow. Started by
 * api-gateway {@code POST /positions/adopt} for a confirmed orphaned broker lot (a broker-held
 * position with no running {@code PositionWorkflow}). Reconstructs a {@code PositionWorkflow} owner
 * from broker truth + the journal so an orphaned-but-legit position can be re-attached and managed
 * normally instead of being force-flattened.
 *
 * <p>This runs as a workflow (not a plain Activity) for one architectural reason: the broker-truth
 * calls ({@link com.ohmytradeagent.contract.activities.ReconciliationExecActivity}) MUST route
 * through the exec task queue ({@code broker-<broker_target>}), and a Temporal activity stub scoped
 * to a task queue can only be created inside a workflow ({@code Workflow.newActivityStub}). It
 * mirrors {@code ReconciliationWorkflowImpl}'s proven exec-stub routing and {@code
 * CopytradeSignalWorkflowImpl}'s child-{@code PositionWorkflow} spawn + {@code onFill} forwarding.
 */
@WorkflowInterface
public interface AdoptionWorkflow {

  /**
   * Adopt the confirmed orphan identified by the input: validate against broker truth (phantom
   * guard), reconstruct the {@code PositionWorkflowInput}, start a {@code PositionWorkflow} owner
   * with the canonical workflow id + search attributes, forward {@code onFill} so the first-fill
   * gate wakes, terminalize the stale journal row, seed the discovery cache, and emit a {@code
   * PositionAdopted} audit with provenance.
   *
   * <p>Idempotent / safe: a no-op ({@link AdoptionResult.Outcome#ALREADY_OWNED}) when a live owner
   * already exists, and refusals ({@link AdoptionResult.Outcome#REFUSED_NOT_HELD} /{@link
   * AdoptionResult.Outcome#REFUSED_NO_ANCHOR}) before any side effect when the broker does not hold
   * the lot or no journal anchor exists.
   */
  @WorkflowMethod
  AdoptionResult adopt(AdoptionWorkflowInput input);
}
