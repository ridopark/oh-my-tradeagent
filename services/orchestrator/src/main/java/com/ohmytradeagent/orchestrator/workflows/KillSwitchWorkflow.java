package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.KillSwitchState;
import com.ohmytradeagent.contract.KillSwitchWorkflowInput;
import com.ohmytradeagent.contract.LivePromotionApprovalRequest;
import com.ohmytradeagent.contract.ResetKillSwitchRequest;
import com.ohmytradeagent.contract.TripKillSwitchRequest;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.UpdateValidatorMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Phase 5 kill switch. One workflow per (tenant_id, strategy_id), bootstrapped on orchestrator
 * startup with workflow_id {@code t-<tenant>/s-<strategy>/killswitch}. Long-running; no
 * continueAsNew in v0 — history is small (handful of state changes per day).
 *
 * <p>State is exposed via {@link #killswitchState()} for {@code risk.check_entry} and mutated via
 * two Updates: {@link #trip(TripKillSwitchRequest)} (operator-issued or auto from the daily-loss
 * heartbeat) and {@link #reset(ResetKillSwitchRequest)} (dual-control reset with a cool-down
 * window).
 */
@WorkflowInterface
public interface KillSwitchWorkflow {

  @WorkflowMethod
  String run(KillSwitchWorkflowInput input);

  /**
   * Reject if already tripped — Validators must be deterministic and have no side effects, so the
   * reject path throws {@link IllegalStateException} with message {@code "already_tripped"} which
   * Temporal surfaces as an Update rejection to the caller.
   */
  @UpdateValidatorMethod(updateName = "trip_killswitch")
  void tripValidator(TripKillSwitchRequest request);

  @UpdateMethod(name = "trip_killswitch")
  void trip(TripKillSwitchRequest request);

  @UpdateValidatorMethod(updateName = "reset_killswitch")
  void resetValidator(ResetKillSwitchRequest request);

  @UpdateMethod(name = "reset_killswitch")
  void reset(ResetKillSwitchRequest request);

  /**
   * Single-operator kill-switch reset issued by a successful one-click {@code
   * LiveActivationWorkflow.activateLive} so activation actually resumes a strategy that a prior
   * one-click deactivate had HALTED (deactivate trips the switch; without this, activate wrote a
   * promotion row but {@code risk.check_entry} stayed fail-closed on {@code tripped==true}). This
   * is an operator decision carried in {@code approverId1} (e.g. {@code "operator:<id>"}); {@code
   * approverId2} is unused. It performs the SAME state mutation and cooldown as {@link
   * #reset(ResetKillSwitchRequest)} but is HONESTLY audited as a single-operator live-activation
   * reset — it is NOT dual-control and must never be used for the manual dual-control reset path.
   */
  @UpdateValidatorMethod(updateName = "reset_on_activation")
  void resetOnActivationValidator(ResetKillSwitchRequest request);

  @UpdateMethod(name = "reset_on_activation")
  void resetOnActivation(ResetKillSwitchRequest request);

  /**
   * Phase 7 prep (issue #87) — dual-control sign-off recording. Issued by api-gateway's {@code POST
   * /promotion/approve}. The Update invokes {@code LivePromotionActivities.approve(request)}, which
   * runs the validator and (on pass) emits one {@code LivePromotionApproved} audit event via the
   * shipped audit-chain writer. Validator rejection ({@code approvers_must_differ}) surfaces to the
   * caller as an Update rejection. No kill-switch state is mutated.
   */
  @UpdateValidatorMethod(updateName = "record_live_promotion")
  void recordLivePromotionValidator(LivePromotionApprovalRequest request);

  @UpdateMethod(name = "record_live_promotion")
  void recordLivePromotion(LivePromotionApprovalRequest request);

  @QueryMethod(name = "killswitch_state")
  KillSwitchState killswitchState();
}
