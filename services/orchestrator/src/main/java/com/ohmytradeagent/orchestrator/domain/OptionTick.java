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
 * <p>Scope note (Issue #266): this is PENNY rounding only. OPRA nickel-tick rules for ≥$3 options
 * are per-symbol (penny-pilot names tick $0.01 even ≥$3) and the codebase has no per-symbol tick
 * table; nickel-grid snapping is deferred to issue #270 (needs operator tick-policy input).
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
