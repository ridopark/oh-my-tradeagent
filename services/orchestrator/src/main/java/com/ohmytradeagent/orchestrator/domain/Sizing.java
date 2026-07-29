package com.ohmytradeagent.orchestrator.domain;

import com.ohmytradeagent.contract.CopytradeSignalPayload;
import com.ohmytradeagent.contract.StrategyConfig;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

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
    return computeEntry(payload, config, capital, limit).contracts();
  }

  /**
   * Cash-weight sizing WITH the scale-in reduction, returning the full {@link EntrySizing} outcome
   * (pre-scale-in base, final contracts, and the matched cue) so the caller audits from one source
   * of truth instead of re-deriving the base/applied/phrase.
   *
   * <p>Scale-in: when {@code entry_scale_in_fraction} is configured AND the BTO tail carries a
   * scale-in cue ("scaling in" / "scale in" / "starter" / "small size" / "half size"), the author
   * is entering small and will add later — so the initial entry is {@code floor(base * fraction)},
   * re-clamped UP to {@code min_contracts} (a sub-minimum entry still floors to min, never zero).
   * Opt-in: null fraction / no cue → the count is byte-identical to {@code base} (full size).
   *
   * <p>Reads {@code payload.getTail()}, an activity-input VALUE. Because scale-in floors UP to min,
   * {@code contracts >= min_contracts} always, so it can never flip an accept into a reject — it
   * adds no new/reordered command, only changes the place-order qty argument. Only Temporal command
   * type/ordering is replay-checked (not activity-input values), so NO Workflow.getVersion gate is
   * needed.
   */
  public static EntrySizing computeEntry(
      CopytradeSignalPayload payload, StrategyConfig config, BigDecimal capital, BigDecimal limit) {
    long base = computeContracts(config, capital, limit);
    BigDecimal fraction = config.getEntryScaleInFraction();
    Optional<String> cue =
        fraction != null ? ScaleInMatcher.match(payload.getTail()) : Optional.empty();
    if (cue.isEmpty()) {
      return new EntrySizing(base, base, false, null);
    }
    long scaled =
        BigDecimal.valueOf(base)
            .multiply(fraction)
            .setScale(0, RoundingMode.FLOOR)
            .longValueExact();
    return new EntrySizing(base, Math.max(config.getMinContracts(), scaled), true, cue.get());
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

  /**
   * Copytrade entry-sizing outcome from {@link #computeEntry}: the pre-scale-in cash-weight {@code
   * base} count, the {@code contracts} to place after any scale-in reduction, and whether a
   * scale-in cue fired ({@code scaleInApplied} + the matched {@code scaleInPhrase}, null when
   * none). Lets the workflow audit the pre/post counts and the cue from one computation rather than
   * re-deriving them.
   */
  public record EntrySizing(
      long base, long contracts, boolean scaleInApplied, String scaleInPhrase) {}
}
