package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.ArmChandelierPayload;
import com.ohmytradeagent.contract.ForceCloseRequest;
import com.ohmytradeagent.contract.ForceCloseResult;
import com.ohmytradeagent.contract.PartialExitRequest;
import com.ohmytradeagent.contract.PositionWorkflowInput;
import com.ohmytradeagent.contract.PremiumTick;
import com.ohmytradeagent.contract.RiskBreachPayload;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.UpdateValidatorMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Long-running workflow that owns the lifecycle of a single open option position. Started by {@link
 * CopytradeSignalWorkflow} after a BTO fills. Receives:
 *
 * <ul>
 *   <li>{@link #partialExit(PartialExitRequest)} — STC dispatch from CopytradeSignalWorkflow.
 *   <li>{@link #onFill(FillEvent)} — exit-fill confirmation from the broker side (Phase 3 uses
 *       test-only signaling; Phase 4 wires the broker fill listener).
 *   <li>{@link #armChandelier(ArmChandelierPayload)} — Phase 4: arm trailing exit on first partial.
 *   <li>{@link #chandelierTick(PremiumTick)} — Phase 4: premium tick from market-data-svc, drives
 *       peak-ratcheting + threshold fire.
 *   <li>{@link #riskBreach(RiskBreachPayload)} — Phase 5: kill-switch cascade. Cancels in-flight
 *       exit, then flattens remaining qty.
 *   <li>{@link #forceClose(ForceCloseRequest)} — Phase 5 Update: operator-initiated force-close.
 * </ul>
 *
 * <p>Completes when remaining qty reaches zero, EOD/expiry forces flatten, or the workflow is
 * externally terminated.
 */
@WorkflowInterface
public interface PositionWorkflow {

  @WorkflowMethod
  String run(PositionWorkflowInput input);

  @SignalMethod
  void partialExit(PartialExitRequest req);

  @SignalMethod
  void onFill(FillEvent event);

  @SignalMethod
  void armChandelier(ArmChandelierPayload payload);

  @SignalMethod
  void chandelierTick(PremiumTick tick);

  /** Phase 5: kill-switch cascade. Cancels any in-flight exit, then flattens remaining qty. */
  @SignalMethod
  void riskBreach(RiskBreachPayload payload);

  @QueryMethod
  TrailingState trailingState();

  /**
   * Phase 5 operator-initiated force-close. Validator rejects blank {@code operator_id} or {@code
   * reason}. Handler enqueues a synthetic exit directive and returns immediately (does not block on
   * broker fills). On already-closed positions ({@code remainingQty == 0}), returns {@code
   * NOOP_ALREADY_CLOSED} without enqueuing.
   */
  @UpdateValidatorMethod(updateName = "force_close")
  void forceCloseValidator(ForceCloseRequest request);

  @UpdateMethod(name = "force_close")
  ForceCloseResult forceClose(ForceCloseRequest request);
}
