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
   * @param limit per-contract price used to divide the allocation. Pass the slip-adjusted limit
   *     when slippage caps are set, so sizing reflects max-acceptable cost rather than the
   *     optimistic mirror. {@code Sizing} stays unaware of how the limit is computed.
   */
  public static long computeContracts(
      CopytradeSignalPayload payload, StrategyConfig config, BigDecimal capital, BigDecimal limit) {
    long base = computeContracts(config, capital, limit);
    // Scale-in entry-size reduction: when entry_scale_in_fraction is configured AND the BTO tail
    // carries a scale-in cue ("scaling in" / "scale in" / "starter" / "small size" / "half size"),
    // the author is entering small and will add later — so we take a smaller initial entry:
    // floor(base * fraction), re-clamped UP to min_contracts (a sub-minimum entry still floors to
    // min, never zero). Opt-in: null fraction / no cue → byte-identical to base (full size).
    //
    // This reads payload.getTail(), an activity-input VALUE. Only Temporal command type/ordering is
    // replay-checked, not activity-input values, so altering the place-order qty here introduces no
    // new/reordered command and needs NO Workflow.getVersion replay gate.
    BigDecimal fraction = config.getEntryScaleInFraction();
    if (fraction != null && ScaleInMatcher.match(payload.getTail()).isPresent()) {
      long scaled =
          BigDecimal.valueOf(base)
              .multiply(fraction)
              .setScale(0, RoundingMode.FLOOR)
              .longValueExact();
      return Math.max(config.getMinContracts(), scaled);
    }
    return base;
  }

  /**
   * Payload-free overload. {@code payload} was never read by the sizing math; the copytrade path
   * delegates here so both keep byte-identical behavior. {@code limit} is the per-contract price.
   */
  public static long computeContracts(StrategyConfig config, BigDecimal capital, BigDecimal limit) {
    long raw = rawContracts(config, capital, limit);
    return Math.max(config.getMinContracts(), Math.min(config.getMaxContracts(), raw));
  }

  /**
   * Decider-aware sizing for the watchlist-trigger path: scales the allocation by the arm and fire
   * size multipliers, then resolves to a SKIP-or-count outcome. With {@code armMult == fireMult ==
   * 1.0} the count is byte-identical to {@link #computeContracts(StrategyConfig, BigDecimal,
   * BigDecimal)}.
   *
   * <p>Unlike the copytrade clamp, a raw below {@code min_contracts} SKIPs rather than flooring up,
   * and a non-positive multiplier SKIPs rather than ever sizing zero.
   */
  public static SizingOutcome computeContractsWithDeciders(
      StrategyConfig config,
      BigDecimal capital,
      BigDecimal limit,
      BigDecimal armMult,
      BigDecimal fireMult) {
    if (armMult.signum() <= 0 || fireMult.signum() <= 0) {
      return new SizingOutcome(true, 0L, "decider-zero");
    }
    long raw = rawContracts(config, capital.multiply(armMult).multiply(fireMult), limit);
    if (raw < config.getMinContracts()) {
      return new SizingOutcome(true, 0L, "below-min");
    }
    return new SizingOutcome(false, Math.min(config.getMaxContracts(), raw), "sized");
  }

  private static long rawContracts(StrategyConfig config, BigDecimal capital, BigDecimal limit) {
    BigDecimal allocation = capital.multiply(config.getCapitalWeight());
    BigDecimal pricePerContract = limit.multiply(CONTRACT_MULTIPLIER);
    if (pricePerContract.signum() <= 0) {
      throw new IllegalArgumentException("limit must be > 0, got: " + limit);
    }
    return allocation.divide(pricePerContract, 0, RoundingMode.FLOOR).longValueExact();
  }

  /** Result of decider-aware sizing: either SKIP (with reason) or a positive contract count. */
  public record SizingOutcome(boolean skip, long contracts, String reason) {}
}
