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

  @QueryMethod(name = "killswitch_state")
  KillSwitchState killswitchState();
}
