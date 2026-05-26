package com.ohmytradeagent.orchestrator.domain;

import com.ohmytradeagent.contract.CopytradeSignalPayload;
import com.ohmytradeagent.contract.StrategyConfig;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure capital-weight sizing math. Determinism-safe — callable from workflow code without I/O.
 *
 * <p>{@code allocation = capital * capital_weight; qty = clamp(floor(allocation / (price * 100)),
 * min_contracts, max_contracts)}. Per PLAN.md Open Question #8.
 */
public final class Sizing {

  /**
   * Standard option contract multiplier (premium dollars per contract = price * 100). Public so
   * risk-svc gates (notional cap, pre-trade-check notional estimate) can share the same constant
   * rather than redeclare it.
   */
  public static final BigDecimal CONTRACT_MULTIPLIER = BigDecimal.valueOf(100);

  private Sizing() {}

  /**
   * @param limit per-contract price used to divide the allocation; pass the slip-adjusted limit
   *     from {@code BtoPricing.computeBtoLimit(...)} so sizing sees max-acceptable cost (Issue
   *     #195). When slippage caps are unset, callers pass the mirror {@code payload.getPrice()} —
   *     {@code Sizing} stays unaware of {@code BtoPricing}.
   */
  public static long computeContracts(
      CopytradeSignalPayload payload, StrategyConfig config, BigDecimal capital, BigDecimal limit) {
    BigDecimal allocation = capital.multiply(config.getCapitalWeight());
    BigDecimal pricePerContract = limit.multiply(CONTRACT_MULTIPLIER);
    if (pricePerContract.signum() <= 0) {
      throw new IllegalArgumentException("limit must be > 0, got: " + limit);
    }
    long raw = allocation.divide(pricePerContract, 0, RoundingMode.FLOOR).longValueExact();
    return Math.max(config.getMinContracts(), Math.min(config.getMaxContracts(), raw));
  }
}
