package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.LiveActivationRequest;
import com.ohmytradeagent.contract.LiveActivationResult;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Phase F (operator-account-onboarding) one-click live ACTIVATION carrier. A short-lived,
 * single-shot workflow started by the api-gateway {@code /admin/.../activate-live} route (a
 * Temporal <em>client</em> cannot dispatch an Activity directly), mirroring {@code
 * StrategyConfigUpdateWorkflow}'s start-and-getResult pattern.
 *
 * <p>A net-new workflow type started fresh per call, so replay determinism is largely trivial (no
 * long-lived history to version). Phase 3b DOES add ONE {@code getVersion} change-point ({@code
 * live-activation-account-cap-aware-v1}) because its account-cap read inserts Activity commands
 * BEFORE existing ones — see {@code LiveActivationWorkflowImpl}. It runs a fail-closed
 * required-config gate + a fresh account probe and, on pass, writes a fresh {@code
 * LivePromotionApproved} row via {@code LivePromotionActivities.activate} so the ALREADY-wired
 * order-time gate at {@code CopytradeSignalWorkflowImpl} keeps firing — this workflow does NOT
 * touch the signal workflow.
 *
 * <p>The companion {@link LiveDeactivationWorkflow#deactivateLive} (deactivation) is a separate
 * {@code @WorkflowInterface} — a Temporal {@code @WorkflowInterface} carries exactly one
 * {@code @WorkflowMethod}, and both run as their own synchronous start-and-getResult call. Both are
 * implemented by the single {@code LiveActivationWorkflowImpl}.
 *
 * <p>Each fail-closed refusal returns a distinct {@link LiveActivationResult} outcome (NOT thrown)
 * so api-gateway maps it to a 422 with the reason; {@code ACTIVATED} maps to 200.
 */
@WorkflowInterface
public interface LiveActivationWorkflow {

  /**
   * Server-side fail-closed activation: verify the stored config is live, the live loss gates are
   * set, {@code capital_source} is a tracking source ({@code account_cash} / {@code
   * account_equity}, #790) or a ceiling-validated {@code static} (#780), the kill switch is
   * armable, and a fresh account probe returns a real account with positive cash; on pass emit a
   * fresh {@code LivePromotionApproved} row. Returns {@code ACTIVATED} (+ {@code
   * expected_account_id}) or one {@code REJECTED_*}.
   */
  @WorkflowMethod
  LiveActivationResult activateLive(LiveActivationRequest request);
}
