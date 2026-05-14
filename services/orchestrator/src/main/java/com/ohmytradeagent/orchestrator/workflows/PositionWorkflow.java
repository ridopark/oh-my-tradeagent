package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.ArmChandelierPayload;
import com.ohmytradeagent.contract.PartialExitRequest;
import com.ohmytradeagent.contract.PositionWorkflowInput;
import com.ohmytradeagent.contract.PremiumTick;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
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
 * </ul>
 *
 * <p>Completes when remaining qty reaches zero, EOD/expiry forces flatten, or the workflow is
 * externally terminated (Phase 5 kill-switch).
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

  @QueryMethod
  TrailingState trailingState();
}
