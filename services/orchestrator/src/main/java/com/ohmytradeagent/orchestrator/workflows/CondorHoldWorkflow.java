package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.RiskBreachPayload;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Gated-condor (PLAN-2026-10-05 Phase 4): owns one filled XSP iron condor from entry to cash
 * settlement. No stops, no targets, no premium exits (the frozen rule) — the only early exits are
 * the operator's {@link #forceClose} and the kill-switch cascade's {@link #riskBreach}, both of
 * which flatten all four legs shorts-first.
 */
@WorkflowInterface
public interface CondorHoldWorkflow {

  /**
   * Returns a short outcome token, e.g. {@code settled} or {@code flattened:killswitch_flatten}.
   */
  @WorkflowMethod
  String run(CondorHoldWorkflowInput input);

  /** Operator flatten. {@code actor} is recorded on the audit. */
  @SignalMethod
  void forceClose(String actor);

  /**
   * Kill-switch cascade ({@code KillSwitchCascadeActivitiesImpl} / the account cap signal every
   * running workflow under the {@code TenantStrategy} search attribute by this name).
   */
  @SignalMethod
  void riskBreach(RiskBreachPayload payload);
}
