package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.KillSwitchState;
import com.ohmytradeagent.contract.KillSwitchWorkflowInput;
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
 * heartbeat) and {@link #reset(ResetKillSwitchRequest)} (single-operator reset with a cool-down
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
   * is an operator decision carried in {@code approverId1} (e.g. {@code "operator:<id>"}). It
   * performs the SAME state mutation and cooldown as the manual {@link
   * #reset(ResetKillSwitchRequest)} path but is audited as a live-activation reset ({@code
   * via=live_activation}) rather than a manual reset ({@code via=manual_reset}).
   */
  @UpdateValidatorMethod(updateName = "reset_on_activation")
  void resetOnActivationValidator(ResetKillSwitchRequest request);

  @UpdateMethod(name = "reset_on_activation")
  void resetOnActivation(ResetKillSwitchRequest request);

  @QueryMethod(name = "killswitch_state")
  KillSwitchState killswitchState();
}
