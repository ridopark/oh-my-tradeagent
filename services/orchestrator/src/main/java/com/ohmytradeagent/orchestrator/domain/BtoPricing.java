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
 * results are deterministic across JDK versions (Issue #191 halt-condition 4). The raw limit is
 * then rounded once to a 2-decimal penny tick via {@link OptionTick#round(BigDecimal)} before the
 * {@link PricedLimit} is built, so no branch can emit a {@code >2 dp} limit that Alpaca's options
 * penny tick rejects with a non-retryable HTTP 422 (Issue #263). The same {@link OptionTick} helper
 * rounds the exit/STC limit so entry and exit stay in lock-step (Issue #266). Downstream
 * comparisons should use {@link BigDecimal#compareTo} rather than {@link BigDecimal#equals} to
 * avoid scale-mismatch surprises (e.g. {@code 3.15} vs {@code 3.150}).
 */
public final class BtoPricing {

  private static final BigDecimal ONE = BigDecimal.ONE;

  /**
   * Applied when {@code repeg_ceiling_pct} is unset.
   *
   * <p>Calibrated against production, NOT chosen for roundness. Every copytrade BTO entry that
   * expired unfilled across the live tenants over a 120-day window was replayed against the option
   * trade tape for its own 90s TTL, giving the ceiling each one would have needed:
   *
   * <pre>
   *   +1.95%  +2.11%  +2.11%  +2.86%  +3.66%  +5.17%  +6.85%  +8.26%
   * </pre>
   *
   * <p>So +5% (today's effective cap) captures 5 of 8, +8% captures 7 of 8, and <b>+10% captures
   * all 8</b>. 12% and 15% capture nothing further and only raise the worst price payable, which is
   * why this is 0.10 and not higher.
   *
   * <p>The bound still matters: it is what refuses a signal whose posted price has gone badly stale
   * (a real case sat at +93% of the signal price), where chasing is simply wrong rather than merely
   * expensive.
   *
   * <p>Trade prints sit at or below the ask, so those percentages are lower bounds — historical
   * options NBBO quotes are not on the current data plan. That shifts the figures by a cent or two,
   * not the choice between 10% and 12%.
   */
  static final BigDecimal DEFAULT_REPEG_CEILING_PCT = new BigDecimal("0.10");

  /** One penny past the ask: enough to cross the spread, not enough to overpay. */
  private static final BigDecimal REPEG_TICK = new BigDecimal("0.01");

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

    BigDecimal rawLimit;
    Strategy strategy;
    if (!hasAbs && !hasPct) {
      rawLimit = price;
      strategy = Strategy.MIRROR;
    } else if (hasAbs && !hasPct) {
      rawLimit = price.add(config.getMaxSlippageAbs());
      strategy = Strategy.SLIP_ABS;
    } else if (!hasAbs && hasPct) {
      rawLimit = price.multiply(ONE.add(config.getMaxSlippagePct()));
      strategy = Strategy.SLIP_PCT;
    } else {
      BigDecimal absLimit = price.add(config.getMaxSlippageAbs());
      BigDecimal pctLimit = price.multiply(ONE.add(config.getMaxSlippagePct()));
      // Ties (compareTo == 0) collapse to the abs branch deterministically; see BtoPricingTest.
      rawLimit = absLimit.compareTo(pctLimit) <= 0 ? absLimit : pctLimit;
      strategy = Strategy.SLIP_MIN;
    }

    // Round ONCE here so EVERY branch emits a broker-accepted penny tick. The SLIP_PCT/SLIP_MIN
    // multiply paths can land on a 3rd decimal (e.g. 1.35 * 1.10 = 1.485); Alpaca's options penny
    // tick rejects >2 dp with a non-retryable HTTP 422, killing the workflow before any order is
    // placed (Issue #263). Routed through the shared OptionTick helper so the entry and exit/STC
    // limit paths round identically (Issue #266); HALF_UP rounds a boundary penny up for a buy
    // limit (very slightly more marketable — the safe entry direction).
    BigDecimal limit = OptionTick.round(rawLimit);
    return new PricedLimit(limit, strategy);
  }

  /**
   * Ceiling the single BTO entry re-peg may walk toward (PLAN-2026-08-04-bto-entry-repeg). This is
   * deliberately WIDER than {@link #computeBtoLimit}, which stays the INITIAL peg: the tight limit
   * goes out first, and this budget is only reachable after that peg has failed to fill.
   *
   * <p>Unset (or ZERO, matching this class's existing null/ZERO equivalence) applies {@link
   * #DEFAULT_REPEG_CEILING_PCT} — the re-peg ships ACTIVE, so unset means "use the default", not
   * "disabled". The off-switch is {@code repeg_after_ms = 0}, resolved by the workflow.
   *
   * <p>Penny-rounded through the same {@link OptionTick} helper as every other limit so no branch
   * can emit a {@code >2 dp} price for Alpaca to reject with a non-retryable 422 (Issue #263).
   *
   * @return the penny-rounded ceiling; never {@code null}
   */
  public static BigDecimal computeRepegCeiling(
      CopytradeSignalPayload payload, StrategyConfig config) {
    BigDecimal pct = config.getRepegCeilingPct();
    BigDecimal effective = isSet(pct) ? pct : DEFAULT_REPEG_CEILING_PCT;
    return OptionTick.round(payload.getPrice().multiply(ONE.add(effective)));
  }

  /**
   * Limit for the single BTO entry re-peg: one penny tick above the live ask, bounded by {@code
   * ceiling}. Walking to the market rather than jumping to the ceiling is what makes the wider
   * ceiling safe to carry — the extra budget is spent only as far as the market actually demands.
   *
   * <p>Both operands are penny-rounded before the {@link BigDecimal#min} so the result is 2 dp and
   * provably {@code <= ceiling}.
   *
   * <p>Returns {@code null} — meaning DO NOT re-peg, leave the standing order — when:
   *
   * <ul>
   *   <li>{@code ask} is {@code null} or non-positive. Entry fail-safe: no live ask means no
   *       re-peg. This is the INVERSE of the exit path, where a missing quote degrades to a
   *       marketable order because the position must be closed; on entry the safe degrade is not to
   *       buy.
   *   <li>the result is {@code <= initialPeg} — nothing to gain, so degrade to the one-shot entry
   *       rather than burn a cancel/replace round-trip. This also covers a tenant configuring
   *       {@code repeg_ceiling_pct} below {@code max_slippage_pct}, which must never re-peg DOWN.
   * </ul>
   */
  public static BigDecimal computeRepegLimit(
      BigDecimal ask, BigDecimal ceiling, BigDecimal initialPeg) {
    if (ask == null || ask.signum() <= 0) {
      return null;
    }
    BigDecimal target = OptionTick.round(ask.add(REPEG_TICK)).min(ceiling);
    return target.compareTo(initialPeg) > 0 ? target : null;
  }

  private static boolean isSet(BigDecimal v) {
    return v != null && v.signum() != 0;
  }
}
