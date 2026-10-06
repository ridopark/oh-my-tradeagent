package com.ohmytradeagent.contract.activities;

import com.ohmytradeagent.contract.activities.CondorMarketActivity.CondorLeg;
import io.temporal.activity.ActivityInterface;
import java.math.BigDecimal;
import java.util.List;

/**
 * Gated-condor (PLAN-2026-10-05 Phase 4) cross-service contract. Implementation lives in {@code
 * services/exec}; the condor workflows declare a stub against this interface on the {@code
 * broker-<broker_target>} task queue. Paper-only: the implementation rejects any {@code
 * brokerTarget} that is not {@code <provider>-paper} or that does not match the queue it serves.
 *
 * <p>Every order these methods journal carries an intent_key prefixed {@code condor-<tenant>-
 * <strategy>-<attemptId>}, which is how the shared journal's P&L and loss-cap readers exclude them.
 */
@ActivityInterface
public interface CondorExecActivity {

  /**
   * Works ONE opening 4-leg net-credit order down the mid-walk ladder (Phase 3 {@code
   * MidWalkExecutor}). Idempotent per {@code attemptId}: a retry resumes the journaled rungs.
   */
  CondorEntryResult enterCondor(CondorEntryRequest request);

  /**
   * Flattens an open condor with market orders, SHORTS FIRST: each short is bought back and must
   * report filled before any long wing is sold, so the account never holds a naked short.
   * Idempotent per {@code attemptId}.
   */
  CondorFlattenResult flattenCondor(CondorFlattenRequest request);

  /**
   * Settlement reconciliation read: the subset of {@code occSymbols} (padded OCC) the broker still
   * holds, long or short, with the signed quantity.
   */
  List<HeldLeg> heldCondorLegs(String tenantId, String brokerTarget, List<String> occSymbols);

  /**
   * @param legs short call, short put, long call, long put (the {@link CondorMarketActivity} order)
   * @param modelCredit the net credit the walk is measured against; it abandons below {@code
   *     modelCredit − 2 ticks}
   * @param deadlineEpochMs the walk sends no rung at/after this instant
   */
  record CondorEntryRequest(
      String tenantId,
      String strategyId,
      String brokerTarget,
      String attemptId,
      long qty,
      List<CondorLeg> legs,
      BigDecimal modelCredit,
      BigDecimal tick,
      long deadlineEpochMs) {}

  /**
   * @param outcome {@code FILLED}, {@code PARTIAL}, {@code ABANDONED} or {@code HALTED}
   * @param avgFillCredit positive per-combo credit; null unless FILLED/PARTIAL
   */
  record CondorEntryResult(
      String outcome,
      BigDecimal netMid,
      long filledQty,
      BigDecimal avgFillCredit,
      BigDecimal slippageVsMid,
      int rungs,
      String reason) {}

  record CondorFlattenRequest(
      String tenantId,
      String strategyId,
      String brokerTarget,
      String attemptId,
      long qty,
      List<CondorLeg> legs) {}

  /**
   * @param shortsCovered every short leg reported filled (only then were the longs sold)
   * @param reason null on a full flatten; otherwise why the longs were left in place
   */
  record CondorFlattenResult(boolean shortsCovered, boolean longsClosed, String reason) {}

  /** {@code qty} is signed: negative for a short. */
  record HeldLeg(String occSymbol, long qty) {}
}
