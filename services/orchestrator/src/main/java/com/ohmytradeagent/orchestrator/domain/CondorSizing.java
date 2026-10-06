package com.ohmytradeagent.orchestrator.domain;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.activities.CondorMarketActivity.CondorLeg;
import java.math.BigDecimal;
import java.util.List;

/**
 * Gated-condor sizing (pure, determinism-safe). Defined-risk: each condor's worst case is known at
 * order-build time, so the per-contract "price" the shared {@link Sizing} clamp divides by is the
 * MAX RISK, not the premium:
 *
 * <pre>
 *   maxRiskPerShare = max(call wing width, put wing width) − expected net credit
 *   contracts       = clamp(floor(capital × capital_weight / (maxRiskPerShare × 100)),
 *                           min_contracts, max_contracts)
 * </pre>
 *
 * <p>Degenerate cases clamp to {@code min_contracts} and page nothing: a credit at/above the width
 * (maxRisk ≤ 0 — no defined loss to divide by), an allocation smaller than one contract's max risk,
 * or no capital base (null/zero — an account read that failed). Never above {@code max_contracts}.
 *
 * <p>FEE CLIFF: XSP carries a $0 exchange fee only under 10 contracts per order. Raising {@code
 * max_contracts} past 9 lets a single condor order reach the fee tier the backtest's cost model did
 * not include.
 */
public final class CondorSizing {

  /** {@code maxRiskPerContract} is in dollars (×100); null when not computable (degenerate). */
  public record Result(long contracts, BigDecimal maxRiskPerContract, BigDecimal allocation) {}

  private CondorSizing() {}

  public static Result size(
      StrategyConfig config, BigDecimal capital, List<CondorLeg> legs, BigDecimal netCredit) {
    long min = config.getMinContracts();
    BigDecimal width = maxWingWidth(legs);
    BigDecimal maxRiskPerShare = netCredit == null ? null : width.subtract(netCredit);
    BigDecimal allocation = capital == null ? null : capital.multiply(config.getCapitalWeight());
    if (maxRiskPerShare == null
        || maxRiskPerShare.signum() <= 0
        || capital == null
        || capital.signum() <= 0) {
      return new Result(Math.min(min, config.getMaxContracts()), null, allocation);
    }
    return new Result(
        Sizing.computeContracts(config, capital, maxRiskPerShare),
        maxRiskPerShare.multiply(Sizing.CONTRACT_MULTIPLIER),
        allocation);
  }

  /** The wider of the two spreads: {@code |long strike − short strike|} per right. */
  static BigDecimal maxWingWidth(List<CondorLeg> legs) {
    int width = 0;
    for (CondorLeg s : legs) {
      if (!"sell".equalsIgnoreCase(s.side())) {
        continue;
      }
      for (CondorLeg l : legs) {
        if ("buy".equalsIgnoreCase(l.side()) && l.type().equals(s.type())) {
          width = Math.max(width, Math.abs(l.strike() - s.strike()));
        }
      }
    }
    return BigDecimal.valueOf(width);
  }
}
