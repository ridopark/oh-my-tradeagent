package com.ohmytradeagent.orchestrator.workflows;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Gated-condor (PLAN-2026-10-05 Phase 4) daily session for one (tenant, strategy): day filters →
 * IV-richness gate (audited EVERY evaluated day) → if gated in, resolve the XSP iron condor and
 * work it down the mid-walk ladder → on a fill, hand the position to a child {@link
 * CondorHoldWorkflow}. Started by {@code CondorScheduleBootstrapper}'s schedule at {@code
 * condor_entry_et} − 10 min.
 */
@WorkflowInterface
public interface CondorSessionWorkflow {

  /** Returns a short outcome token for audit/testing, e.g. {@code skip:fomc} or {@code filled}. */
  @WorkflowMethod
  String run(CondorSessionWorkflowInput input);
}
