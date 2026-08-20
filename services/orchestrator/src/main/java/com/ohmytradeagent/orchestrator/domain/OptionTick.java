package com.ohmytradeagent.orchestrator.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Shared, pure option limit-price tick rounding (Issue #266 Gap A).
 *
 * <p>Both the entry limit ({@link BtoPricing#computeBtoLimit}) and the exit/STC limit ({@code
 * PositionWorkflowImpl.exitIntent}) must emit a 2-decimal penny tick: Alpaca's options penny tick
 * rejects a {@code >2 dp} limit with a non-retryable HTTP 422. On the entry path that kills the
 * CopytradeSignalWorkflow before any order is placed; on the exit path it FAILS the position close,
 * stranding a live position with no STC — strictly worse. Centralizing the rounding here removes
 * the divergence risk of two inline {@code setScale} call sites.
 *
 * <p>Determinism contract: this is pure {@link BigDecimal#setScale(int, RoundingMode)} math with no
 * clock, I/O, or randomness, so it is safe to call from Temporal workflow code (e.g. {@code
 * exitIntent}) and replays byte-identically across JDK versions — consistent with {@link
 * BtoPricing}'s existing determinism-safety contract (Issue #191).
 *
 * <p>{@link RoundingMode#HALF_UP} is the standard, least-surprising direction. A {@code null} limit
 * (MARKET-order flatten) is passed through unchanged — there is no price to round.
 *
 * <p>Scope note: this is PENNY rounding only, and that is the FINAL policy — not a gap awaiting
 * work. Issue #270 tracked snapping ≥$3 limits to the OPRA $0.05 nickel grid; it was closed
 * 2026-08-20 as not-reproducible against this broker, on 90 days of live order flow:
 *
 * <ul>
 *   <li>42 live orders carried a ≥$3 limit deliberately OFF the nickel grid. 28 filled, 14 were
 *       ordinary TTL/re-peg cancels, and ZERO were rejected. Alpaca does not enforce OPRA nickel
 *       increments on our flow.
 *   <li>The only price-shaped 422s on record complain about DECIMAL PLACES, not a grid ("limit
 *       price must be limited to 2 decimal places" — ARM at 11.6750, SPY at 2.6750, both paper,
 *       both June 2026). That is precisely what this class fixes.
 * </ul>
 *
 * <p><b>Do not add nickel snapping.</b> It would over-coarsen penny-pilot names that legitimately
 * tick $0.01 above $3, and on a SELL limit it rounds the wrong way — systematically widening every
 * ≥$3 exit limit to avoid a rejection that does not occur. Exits are where slippage compounds.
 *
 * <p>This finding is about ALPACA, not about OPRA. {@code broker_target} also admits
 * tradier/ibkr/schwab; a future broker may enforce the nickel grid, at which point the policy is
 * per-broker and this note is the starting evidence, not a reason to skip re-measuring.
 */
public final class OptionTick {

  private OptionTick() {}

  /**
   * Rounds an option limit price to a 2-decimal penny tick using {@link RoundingMode#HALF_UP}.
   *
   * @param limit the raw limit price, or {@code null} for a MARKET order (passed through)
   * @return the penny-rounded limit (scale 2), or {@code null} if {@code limit} was {@code null}
   */
  public static BigDecimal round(BigDecimal limit) {
    return limit == null ? null : limit.setScale(2, RoundingMode.HALF_UP);
  }
}
