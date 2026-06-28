package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.LiveActivationResult;
import com.ohmytradeagent.contract.LiveDeactivationRequest;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Phase F (operator-account-onboarding) one-click live DEACTIVATION carrier. Companion to {@link
 * LiveActivationWorkflow} — a separate {@code @WorkflowInterface} because a Temporal
 * {@code @WorkflowInterface} carries exactly one {@code @WorkflowMethod}. Both are implemented by
 * the single {@code LiveActivationWorkflowImpl} and started fresh per call (no {@code getVersion}).
 *
 * <p>Deactivation emits a {@code LivePromotionDeactivated} row (so the order-time gate fails closed
 * on the next live BTO) AND trips the per-{@code (tenant, strategy)} kill switch so no live order
 * can slip through even before the next gate read.
 */
@WorkflowInterface
public interface LiveDeactivationWorkflow {

  /**
   * Emit the {@code LivePromotionDeactivated} row and trip the kill switch. Returns {@code
   * DEACTIVATED}.
   */
  @WorkflowMethod
  LiveActivationResult deactivateLive(LiveDeactivationRequest request);
}
