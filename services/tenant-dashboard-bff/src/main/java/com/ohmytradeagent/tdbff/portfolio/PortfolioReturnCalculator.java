package com.ohmytradeagent.tdbff.portfolio;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * PURE (no I/O, no Temporal) deposit-adjusted range-return calculator for the {@code /live} header.
 *
 * <p>Alpaca's own {@code profit_loss}/{@code no_reset} figures count cash deposits as profit — the
 * live acct 847309116, funded from $5k with a +$41,230 transfer, reported +$47,259 / +945% for a 1M
 * range. This computes the TRUE trading P&L over the selected range from the equity series net of
 * cash flows:
 *
 * <ul>
 *   <li>{@code rangePl = EV − BV − NetFlows} (dollars)
 *   <li>{@code rangePlPct} = Modified-Dietz money-weighted return = {@code (EV − BV − NetFlows) /
 *       (BV + Σ w_i·F_i)}, where {@code w_i = (T1 − t_i)/(T1 − T0)}.
 * </ul>
 *
 * <p>Any input that would make the number untruthful or undefined (flows unavailable, missing
 * base/equity, zero/negative denominator, degenerate window) yields a null on that field rather
 * than a deposit-polluted or divide-by-zero result — the UI renders "—".
 */
@Component
public class PortfolioReturnCalculator {

  // 20 significant digits is ample for a currency ratio; HALF_UP matches everyday rounding.
  private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

  /**
   * Cash-flow timestamps are day-granular (midnight UTC); see the window note in {@link #compute}.
   */
  private static final long SECONDS_PER_DAY = 86_400L;

  /** Deposit-adjusted range return; either field may be null when undefined (UI renders "—"). */
  public record RangeReturn(BigDecimal rangePl, BigDecimal rangePlPct) {}

  private static final RangeReturn NULL = new RangeReturn(null, null);

  /**
   * @param equity account net-liquidation equity series (chart line); {@code equity[last]} = EV.
   * @param baseValue the range-start baseline (BV); null → both fields null.
   * @param timestamps epoch-seconds index parallel to {@code equity}; {@code [0]}=T0, {@code
   *     [last]}=T1.
   * @param flowTimestamps epoch-seconds of each in-window cash flow (parallel to {@code
   *     flowAmounts}).
   * @param flowAmounts signed cash-flow amounts (deposit +, withdrawal −).
   * @param flowsAvailable false/null when the activities read failed → both fields null (never show
   *     a deposit-polluted number).
   * @param baseValueAsof epoch-seconds of the range baseline's as-of date (Alpaca {@code
   *     base_value_asof}); any cash flow dated at/before it is already baked into {@code baseValue}
   *     and is EXCLUDED so the initial funding is not subtracted twice. Null → fall back to the
   *     {@code timestamps[0]}-derived window (behavior-preserving when the field is absent).
   * @param liveEquity live account net-liquidation equity — the SAME figure the {@code /live}
   *     header total uses. Used as EV (end value) when non-null so a daily-bar range (1M/3M/YTD/1Y)
   *     values the book at NOW, not at the series' last point (the last COMPLETED session =
   *     yesterday's close), which otherwise overstates the range by today's move. Null → fall back
   *     to {@code equity[last]} (behavior-preserving when the live snapshot is unavailable).
   * @param evAsOfEpochSecond epoch-seconds the EV is valued AS-OF — passed only when {@code
   *     liveEquity} is a live NOW snapshot (daily-bar ranges). When both are present the cash-flow
   *     window's UPPER bound is this as-of time (not {@code timestamps[last]}), so a deposit made
   *     TODAY — which is baked into the live EV but dated AFTER the series' last point — is netted
   *     out instead of counted as profit; the Modified-Dietz span and weights use it too. Null (or
   *     {@code liveEquity} null) → the upper bound stays {@code timestamps[last]}
   *     (behavior-preserving).
   */
  public RangeReturn compute(
      List<BigDecimal> equity,
      BigDecimal baseValue,
      List<Long> timestamps,
      List<Long> flowTimestamps,
      List<BigDecimal> flowAmounts,
      Boolean flowsAvailable,
      Long baseValueAsof,
      BigDecimal liveEquity,
      Long evAsOfEpochSecond) {
    if (!Boolean.TRUE.equals(flowsAvailable)) {
      return NULL;
    }
    if (equity == null || equity.isEmpty() || timestamps == null || timestamps.isEmpty()) {
      return NULL;
    }
    if (baseValue == null) {
      return NULL;
    }
    // EV = live account equity when available (the header total's value, valued at NOW), else the
    // series' last point. For a daily-bar range the last point is the last COMPLETED session
    // (yesterday's close), so falling to it would overstate the range by today's intraday move; the
    // live-equity EV keeps the range reconciled with "total − funded" and matches the 1W value.
    BigDecimal ev = liveEquity != null ? liveEquity : equity.get(equity.size() - 1);
    if (ev == null) {
      return NULL;
    }

    long t0 = timestamps.get(0);
    // The EV's as-of time is the flow window's upper bound whenever EV is the live NOW snapshot
    // (liveEquity + evAsOf both present). Otherwise EV is the series' last point, so the window
    // ends
    // exactly there — behavior-preserving. This keeps the flow window coupled to the EV valuation:
    // a today-dated deposit that sits inside the live EV is admitted (and netted) rather than being
    // dropped as "after the series" and re-inflating the range by the deposit.
    long t1Effective =
        (liveEquity != null && evAsOfEpochSecond != null)
            ? evAsOfEpochSecond
            : timestamps.get(timestamps.size() - 1);

    // Modified-Dietz money-weighting anchor (T0). base_value_asof is the AUTHORITATIVE baseline
    // instant: base_value is valued as-of that date and every flow at/before it is already baked in
    // (excluded from netFlows below). The weighting window MUST start there too — the
    // flow-exclusion
    // lower bound and the weighting lower bound have to be the SAME anchor or they contradict.
    //
    // Using timestamps.get(0) here instead is the divergent-% bug: for a range longer than the
    // account's funded history (e.g. 1Y on a ~1-month-old account) Alpaca pads the equity series
    // BACK before the account was funded, so the series' first timestamp sits in a pre-capital dead
    // zone. That stretches the span, shrinking every in-window deposit's weight w_i =
    // (T1−t_i)/(T1−T0)
    // and thus the denominator (BV + Σ w_i·F_i), inflating the % — WITHOUT changing the $ (which is
    // EV−BV−netFlows). The visible tell: 1M/3M/YTD/1Y show the SAME dollars but a % that climbs
    // with
    // range length. Anchoring T0 to base_value_asof makes the weighting independent of how far
    // Alpaca
    // padded the series, so ranges sharing an inception baseline yield the SAME %, matching the $.
    // Fall back to the series' first point only when base_value_asof is absent (legacy path).
    long windowStart = baseValueAsof != null ? baseValueAsof : t0;
    boolean spanPositive = t1Effective > windowStart;
    BigDecimal span = spanPositive ? BigDecimal.valueOf(t1Effective - windowStart) : null;

    // The two timestamp series have DIFFERENT granularity, and comparing them exactly is a bug.
    // Cash flows come from Alpaca's non-trade activities, which carry only a "date" — the exec
    // adapter parses it to MIDNIGHT UTC. Equity timestamps are MARKET time: a 1D bar for 2026-07-15
    // lands around 20:00Z. So a deposit made on the range's FIRST day has
    // t(=00:00Z) < t0(=20:00Z) and an exact `t < t0` filter silently drops it — which puts the
    // deposit straight back into EV − BV as if it were profit. That is precisely the +945%
    // inflation this class exists to remove, just narrowed to ranges that begin on a transfer day.
    // Fix: admit any flow dated on t0's calendar day by flooring the lower bound to that day's UTC
    // midnight. The UPPER bound is t1Effective (above): the series' last point by default, or the
    // EV's live as-of time when EV is valued at NOW — so a TODAY-dated flow baked into the live EV
    // is admitted rather than dropped as "after the series" and re-counted as profit.
    //
    // When base_value_asof is present, it is the AUTHORITATIVE lower bound: base_value already
    // bakes
    // in every cash flow dated at/before that as-of date (notably the initial funding deposit), so
    // a
    // flow dated <= base_value_asof must be EXCLUDED — counting it would subtract the funding twice
    // and sign-flip a real profit into a loss. A flow dated AFTER base_value_asof (e.g. the range's
    // first-day deposit) is still counted and weight-clamped below. The t0-floored window is the
    // fallback used only when base_value_asof is null (behavior-preserving for the legacy path).
    long fallbackWindowStart = Math.floorDiv(t0, SECONDS_PER_DAY) * SECONDS_PER_DAY;

    BigDecimal netFlows = BigDecimal.ZERO;
    BigDecimal weightedFlows = BigDecimal.ZERO;
    int n = Math.min(size(flowTimestamps), size(flowAmounts));
    for (int i = 0; i < n; i++) {
      Long t = flowTimestamps.get(i);
      BigDecimal amount = flowAmounts.get(i);
      boolean beforeWindow =
          baseValueAsof != null
              ? t != null && t <= baseValueAsof
              : t != null && t < fallbackWindowStart;
      if (t == null || amount == null || beforeWindow || t > t1Effective) {
        // Defensively ignore out-of-window / malformed / already-in-base flows.
        continue;
      }
      netFlows = netFlows.add(amount);
      if (spanPositive) {
        // w_i = (T1 − t_i)/(T1 − T0), CLAMPED to 1. A first-day flow sits at midnight, before t0,
        // so the raw ratio exceeds 1 and would over-weight the deposit in the denominator
        // (understating the return). Capping at 1 states the truth for that case: the money was in
        // the account for the whole window.
        BigDecimal weight =
            BigDecimal.valueOf(t1Effective - t).divide(span, MC).min(BigDecimal.ONE);
        weightedFlows = weightedFlows.add(weight.multiply(amount));
      }
    }

    BigDecimal rangePl = ev.subtract(baseValue).subtract(netFlows);

    BigDecimal rangePlPct = null;
    if (spanPositive) {
      BigDecimal denominator = baseValue.add(weightedFlows);
      if (denominator.signum() > 0) {
        rangePlPct = rangePl.divide(denominator, MC);
      }
    }
    return new RangeReturn(rangePl, rangePlPct);
  }

  private static int size(List<?> list) {
    return list == null ? 0 : list.size();
  }
}
