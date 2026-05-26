package com.ohmytradeagent.orchestrator.domain;

import com.ohmytradeagent.contract.CopytradeSignalPayload;
import com.ohmytradeagent.contract.StrategyConfig;
import java.math.BigDecimal;

/**
 * Pure BTO limit-price computation. Determinism-safe — callable from workflow code without I/O,
 * clocks, randomness, or system time (Issue #191 halt-condition 3).
 *
 * <p>Precedence (Issue #191):
 *
 * <ul>
 *   <li>both caps {@code null}/{@code ZERO} → {@link Strategy#MIRROR}, return {@code price}
 *       (back-compat with pre-#191 behavior).
 *   <li>only {@code max_slippage_abs} → {@link Strategy#SLIP_ABS}, return {@code price + abs}.
 *   <li>only {@code max_slippage_pct} → {@link Strategy#SLIP_PCT}, return {@code price * (1 +
 *       pct)}.
 *   <li>both → {@link Strategy#SLIP_MIN}, return {@code min(price + abs, price * (1 + pct))} with
 *       ties broken to the abs branch (deterministic).
 * </ul>
 *
 * <p>{@code null} and {@link BigDecimal#ZERO} (by {@link BigDecimal#signum} == 0) are treated
 * identically so parser-emitted {@code "0.00"} values collapse to MIRROR without crashing.
 *
 * <p>BigDecimal scale policy: {@link BigDecimal#add} and {@link BigDecimal#multiply} have
 * JDK-spec'd scale arithmetic (scale of {@code a.add(b)} = {@code max(a.scale, b.scale)}; scale of
 * {@code a.multiply(b)} = {@code a.scale + b.scale}). No {@link java.math.MathContext} is needed —
 * results are deterministic across JDK versions (Issue #191 halt-condition 4). Downstream
 * comparisons should use {@link BigDecimal#compareTo} rather than {@link BigDecimal#equals} to
 * avoid scale-mismatch surprises (e.g. {@code 3.15} vs {@code 3.150}).
 */
public final class BtoPricing {

  private static final BigDecimal ONE = BigDecimal.ONE;

  private BtoPricing() {}

  /**
   * Branch tag emitted into the {@code OrderSubmitted} audit subject. The {@link #wireKey} value is
   * the contract — Java enum constants may be renamed without changing the wire-format string
   * downstream consumers parse.
   */
  public enum Strategy {
    MIRROR("mirror"),
    SLIP_ABS("slip_abs"),
    SLIP_PCT("slip_pct"),
    SLIP_MIN("slip_min");

    private final String wireKey;

    Strategy(String wireKey) {
      this.wireKey = wireKey;
    }

    public String wireKey() {
      return wireKey;
    }
  }

  /** Carrier for the limit price + branch taken. */
  public record PricedLimit(BigDecimal limit, Strategy strategy) {}

  public static PricedLimit computeBtoLimit(CopytradeSignalPayload payload, StrategyConfig config) {
    BigDecimal price = payload.getPrice();
    boolean hasAbs = isSet(config.getMaxSlippageAbs());
    boolean hasPct = isSet(config.getMaxSlippagePct());

    if (!hasAbs && !hasPct) {
      return new PricedLimit(price, Strategy.MIRROR);
    }
    if (hasAbs && !hasPct) {
      return new PricedLimit(price.add(config.getMaxSlippageAbs()), Strategy.SLIP_ABS);
    }
    if (!hasAbs && hasPct) {
      return new PricedLimit(
          price.multiply(ONE.add(config.getMaxSlippagePct())), Strategy.SLIP_PCT);
    }
    BigDecimal absLimit = price.add(config.getMaxSlippageAbs());
    BigDecimal pctLimit = price.multiply(ONE.add(config.getMaxSlippagePct()));
    // Ties (compareTo == 0) collapse to the abs branch deterministically; see BtoPricingTest.
    BigDecimal min = absLimit.compareTo(pctLimit) <= 0 ? absLimit : pctLimit;
    return new PricedLimit(min, Strategy.SLIP_MIN);
  }

  private static boolean isSet(BigDecimal v) {
    return v != null && v.signum() != 0;
  }
}
