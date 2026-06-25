package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.ArmChandelierPayload;
import com.ohmytradeagent.contract.FillSignalPayload;
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
 *   <li>{@link #onFill(FillSignalPayload)} — exit-fill confirmation from the broker side (Phase 3
 *       uses test-only signaling; Phase 4 wires the broker fill listener).
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
  void onFill(FillSignalPayload event);

  @SignalMethod
  void armChandelier(ArmChandelierPayload payload);

  @SignalMethod
  void chandelierTick(PremiumTick tick);

  /** Phase 5: kill-switch cascade. Cancels any in-flight exit, then flattens remaining qty. */
  @SignalMethod
  void riskBreach(RiskBreachPayload payload);

  /**
   * Edited-signal supersede (F1): the parent {@link CopytradeSignalWorkflow} has detected that THIS
   * leg's expiry was corrected by a follow-up BTO posted within the correction window, and that all
   * supersede guardrails held (same tenant/strategy/underlying/strike/right, different expiry,
   * just-filled, not partially exited). Cancels any in-flight exit then drives an immediate market
   * flatten of the remaining qty (cancel/replace of the wrong-expiry leg). Version-gated in the
   * impl ({@code bto-correction-supersede-v1}): a no-op on pre-F1 in-flight workflows so their
   * recorded histories replay byte-identically. Multi-arg (no JSON-schema DTO) to keep F1
   * Java-only.
   *
   * @param correctedSignalId the corrected BTO's signal_id (recorded on the supersede audit)
   * @param correctedOcc the corrected leg's OCC option symbol (recorded on the supersede audit)
   */
  @SignalMethod
  void supersede(String correctedSignalId, String correctedOcc);

  @QueryMethod
  TrailingState trailingState();

  /**
   * Issue #318: open-position state for the portfolio-level risk gates. Synchronous, non-mutating —
   * exposes the OCC contract, remaining (post-partial) qty, and per-contract entry premium so the
   * Visibility-backed {@link com.ohmytradeagent.orchestrator.activities.PortfolioSnapshot} can
   * value the open book.
   */
  @QueryMethod
  PositionState positionState();

  /**
   * Dashboard exit-proximity snapshot: stop/target/trail levels and the most recent evaluated bid
   * for an armed watchlist-exit position. Synchronous, non-mutating — does not append to history.
   * Reports {@code armed=false} with null levels on positions that never armed the watchlist exit.
   */
  @QueryMethod
  ExitProximityView exitProximity();

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
