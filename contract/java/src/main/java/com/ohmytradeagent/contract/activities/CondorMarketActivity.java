package com.ohmytradeagent.contract.activities;

import io.temporal.activity.ActivityInterface;
import java.math.BigDecimal;
import java.util.List;

/**
 * Gated-condor (PLAN-2026-10-05 Phase 2) cross-service contract. Implementation lives in {@code
 * services/market-data}; the condor session workflow declares a stub against this interface on the
 * {@code market-data} task queue. Everything non-deterministic the condor workflows need (live
 * chain quotes, historical bars) is behind these reads.
 *
 * <p>Neither method throws on missing data: a result with a non-null {@code reason} means "could
 * not evaluate" and the caller skips the day (fail-closed, {@code gate=false} / no legs).
 */
@ActivityInterface
public interface CondorMarketActivity {

  /**
   * Today's ATM-straddle richness at {@code entryEt} ({@code HH:mm} ET) versus the trailing {@code
   * lookbackDays} distribution. richness = straddle / (0.7979 · σ₁ₘᵢₙ(09:30→entry) · √(minutes to
   * 16:00) · spot); {@code gate} = richness ≥ trailing median.
   */
  RichnessResult evaluateRichness(String underlying, String entryEt, int lookbackDays);

  /**
   * Today's 0DTE iron condor on {@code underlying}: short call/put at ±{@code shortOffsetPct}% from
   * spot, long wings at ±{@code wingOffsetPct}%, each strike rounded OUTWARD to the $1 grid (the
   * backtest's rule), with live NBBO per leg and the net-credit mid.
   */
  CondorLegsResult resolveCondorLegs(
      String underlying, double shortOffsetPct, double wingOffsetPct);

  /**
   * Today's settlement-proxy spot: the put-call-parity spot of {@code underlying}'s 0DTE strip from
   * the last option BARS before 16:00 ET (the same parity method and bar basis as the richness
   * gate). Null when no strip is available. The condor hold workflow prices intrinsic-at-close from
   * it to reconcile against the broker's settlement.
   */
  Double settlementSpot(String underlying);

  /**
   * @param trailingQuantile fraction of the trailing values ≤ today's richness
   * @param reason null when evaluated; otherwise why the day could not be evaluated (gate=false)
   */
  record RichnessResult(
      Double richness,
      Double trailingMedian,
      Double trailingQuantile,
      int trailingCount,
      boolean gate,
      String reason) {}

  /**
   * One condor leg. {@code side} is {@code sell} (shorts) or {@code buy} (wings); {@code type} is
   * {@code C} or {@code P}; {@code occSymbol} is the space-padded canonical OCC.
   */
  record CondorLeg(
      String occSymbol, String side, String type, int strike, BigDecimal bid, BigDecimal ask) {}

  /**
   * @param legs short call, short put, long call, long put — in that order; empty when {@code
   *     reason} is set
   * @param netCreditMid Σ short mids − Σ long mids (per-share)
   */
  record CondorLegsResult(
      Double spot, List<CondorLeg> legs, BigDecimal netCreditMid, String reason) {}
}
