package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.AccountKillSwitchWorkflowInput;
import com.ohmytradeagent.contract.KillSwitchState;
import com.ohmytradeagent.contract.ResetKillSwitchRequest;
import com.ohmytradeagent.contract.TripKillSwitchRequest;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.UpdateValidatorMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Phase 6 account-level kill switch. ONE workflow per tenant (workflow_id {@code
 * t-<tenant>/account/killswitch}), bootstrapped on orchestrator startup. The cap spans EVERY
 * strategy on the tenant's shared {@code broker_target}: each heartbeat sums tenant-wide realized
 * PnL + tenant-wide open mark-to-market and auto-trips when the total crosses {@code
 * -account_daily_loss_threshold}. On trip it MARKET-flattens every running PositionWorkflow across
 * all the tenant's strategies (account-scoped cascade).
 *
 * <p>Opt-in / inert: when {@code account_daily_loss_threshold} is unset the heartbeat never trips —
 * no behavioral change for tenants that have not opted in. The per-(tenant, strategy) {@link
 * KillSwitchWorkflow} is unaffected and continues to run independently.
 *
 * <p>State + dual-control (trip/reset) mirror {@link KillSwitchWorkflow}.
 */
@WorkflowInterface
public interface AccountKillSwitchWorkflow {

  @WorkflowMethod
  String run(AccountKillSwitchWorkflowInput input);

  @UpdateValidatorMethod(updateName = "trip_account_killswitch")
  void tripValidator(TripKillSwitchRequest request);

  @UpdateMethod(name = "trip_account_killswitch")
  void trip(TripKillSwitchRequest request);

  @UpdateValidatorMethod(updateName = "reset_account_killswitch")
  void resetValidator(ResetKillSwitchRequest request);

  @UpdateMethod(name = "reset_account_killswitch")
  void reset(ResetKillSwitchRequest request);

  @QueryMethod(name = "account_killswitch_state")
  KillSwitchState killswitchState();
}
