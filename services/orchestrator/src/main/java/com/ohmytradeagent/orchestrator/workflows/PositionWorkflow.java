package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.ArmChandelierPayload;
import com.ohmytradeagent.contract.ArmTrailRequest;
import com.ohmytradeagent.contract.ArmTrailResult;
import com.ohmytradeagent.contract.FillSignalPayload;
import com.ohmytradeagent.contract.ForceCloseRequest;
import com.ohmytradeagent.contract.ForceCloseResult;
import com.ohmytradeagent.contract.PartialCloseRequest;
import com.ohmytradeagent.contract.PartialCloseResult;
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
 *   <li>{@link #partialClose(PartialCloseRequest)} — Update: operator-initiated partial close
 *       ("Trim"), sells a fraction of the remaining qty at market and leaves the rest running.
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

  /**
   * Operator-initiated PARTIAL close ("Trim" on the dashboard /live holdings table): sell {@code
   * fraction} of the remaining qty at MARKET and leave the rest of the position running with all of
   * its existing exits intact. The sibling of {@link #forceClose(ForceCloseRequest)} — same
   * exit-NOW pricing — but reduce-only: the validator rejects {@code fraction >= 1}, AND the
   * handler clamps the resolved qty to {@code remainingQty - 1}, so a full close can only ever be a
   * force_close. The clamp is the load-bearing half: {@code fraction < 1} alone does not bound the
   * qty below the lot, because {@code qtyToClose} is {@code ceil}-ed (0.75 of a 3-lot is 3).
   *
   * <p>Priority: a trim drains through the normal FIFO exit queue, BELOW {@code force_close} and
   * {@code risk_breach} (which pre-empt because they flatten everything). A trim behind a queued
   * STC therefore waits for it.
   *
   * <p>The handler adds NO new exit machinery: it synthesizes a {@link PartialExitRequest} ({@code
   * market=true}) onto the SAME {@code pendingExits} deque the STC path feeds, so the trim inherits
   * the qty math, min-partial-qty behavior, fill booking into realized P&amp;L, and the
   * place-failure/late-fill recovery that the partial-exit path already carries.
   *
   * <p>On an already-drained position ({@code remainingQty == 0} once confirmed) returns {@code
   * NOOP_ALREADY_CLOSED} without enqueuing.
   */
  @UpdateValidatorMethod(updateName = "partial_close")
  void partialCloseValidator(PartialCloseRequest request);

  @UpdateMethod(name = "partial_close")
  PartialCloseResult partialClose(PartialCloseRequest request);

  /**
   * Operator-initiated trailing stop ("Stop-loss" on the dashboard /live holdings table):
   * PLAN-2026-08-16-live-operator-trailing-stop. Arms the EXISTING chandelier trail on THIS
   * position only — it adds no second stop mechanism, and it does not touch {@code
   * strategy_config.trail_giveback_pct}, so no other or future position is affected.
   *
   * <p>Deliberately an Update rather than reusing the {@code armChandelier} SIGNAL. An arm can be
   * refused — bad giveback, an anchor that cannot be resolved, or a market-data subscribe failure —
   * and a signal cannot report that back. The operator would be shown "stop set" for a real-money
   * position that is in fact unprotected, a failure indistinguishable from success on screen and
   * discovered only when the stop does not fire.
   *
   * <p>The validator rejects an out-of-range giveback synchronously, which never enters history.
   * The handler is idempotent in the same way {@code processArm} is: a position that is already
   * trailing returns {@code ALREADY_ARMED} unchanged, so a double-click can never LOOSEN a stop
   * that is already protecting the lot.
   */
  @UpdateValidatorMethod(updateName = "arm_trail")
  void armTrailValidator(ArmTrailRequest request);

  @UpdateMethod(name = "arm_trail")
  ArmTrailResult armTrail(ArmTrailRequest request);
}
