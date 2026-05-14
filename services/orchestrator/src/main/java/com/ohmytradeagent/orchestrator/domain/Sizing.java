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

  private static final BigDecimal CONTRACT_MULTIPLIER = BigDecimal.valueOf(100);

  private Sizing() {}

  public static long computeContracts(
      CopytradeSignalPayload payload, StrategyConfig config, BigDecimal capital) {
    BigDecimal allocation = capital.multiply(config.getCapitalWeight());
    BigDecimal pricePerContract = payload.getPrice().multiply(CONTRACT_MULTIPLIER);
    if (pricePerContract.signum() <= 0) {
      throw new IllegalArgumentException("price must be > 0, got: " + payload.getPrice());
    }
    long raw = allocation.divide(pricePerContract, 0, RoundingMode.FLOOR).longValueExact();
    return Math.max(config.getMinContracts(), Math.min(config.getMaxContracts(), raw));
  }
}
