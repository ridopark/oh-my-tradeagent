package com.ohmytradeagent.orchestrator.alert.floorbreach;

import com.ohmytradeagent.orchestrator.alert.floorbreach.MarketDataOptionQuoteClient.OptionQuote;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Issue #779: pure floor-breach evaluation. Given an entry premium, a live NBBO snapshot, and a
 * threshold (fraction of entry premium lost), classifies the position as BREACH / OK / UNKNOWN.
 *
 * <p>The comparison gates on the <b>BID</b>, never the mid: the exit path trades the bid (#690 — a
 * widening book walks the bid through a stop while the mid never moves), so the alert must watch
 * the same number the account bleeds at.
 *
 * <p>Three states, not two. UNKNOWN is load-bearing fail-soft: a missing quote or a missing bid
 * must never render as "all clear" (which would hide a real breach) and must never fire an alert
 * (which would page an operator off a monitoring failure). A zero bid on a DEAD book (no ask
 * either) is UNKNOWN too — {@code snapshotQuote} is unfiltered by the #690 tick guard, so a no-bid
 * no-ask snapshot is untrustworthy. A zero bid with a live ask, by contrast, is the real
 * bled-to-worthless case and must alert as a -100% breach, not vanish into "unknown".
 */
public final class FloorBreachEvaluator {

  /** Default threshold when {@code floor_breach_alert_pct} is absent/null: -50% of entry. */
  public static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("0.50");

  /** The re-arm band multiplier: recovery requires the bid at or above {@code 1.10 x floor}. */
  static final BigDecimal REARM_BAND = new BigDecimal("1.10");

  private FloorBreachEvaluator() {}

  public enum Status {
    BREACH,
    OK,
    UNKNOWN
  }

  /**
   * One evaluation. {@code lossPct} and {@code step} are meaningful only for BREACH ({@code step}
   * is the 10%-bucketed loss depth: -50..-59% → 50, -60..-69% → 60, …, bid==0 → 100). {@code
   * recoveredAboveBand} is meaningful only for OK: true iff the bid sits at or above {@code floor x
   * 1.10}, the hysteresis re-arm line.
   */
  public record Evaluation(
      Status status, BigDecimal lossPct, int step, boolean recoveredAboveBand) {

    static Evaluation unknown() {
      return new Evaluation(Status.UNKNOWN, null, 0, false);
    }
  }

  /**
   * Evaluates one position. {@code threshold} null → {@link #DEFAULT_THRESHOLD}. Never throws.
   *
   * @param entryPremium per-contract entry fill premium (dollars); null/non-positive → UNKNOWN
   * @param quote the NBBO snapshot, or null when market-data was unreachable
   * @param threshold fraction of entry premium lost at which the alert fires (0 &lt; t &lt; 1)
   */
  public static Evaluation evaluate(
      BigDecimal entryPremium, OptionQuote quote, BigDecimal threshold) {
    if (entryPremium == null || entryPremium.signum() <= 0) {
      return Evaluation.unknown();
    }
    if (quote == null || quote.bid() == null) {
      return Evaluation.unknown();
    }
    BigDecimal t = threshold == null ? DEFAULT_THRESHOLD : threshold;
    BigDecimal bid = quote.bid();
    if (bid.signum() == 0) {
      BigDecimal ask = quote.ask();
      if (ask == null || ask.signum() == 0) {
        // Dead book: an unfiltered snapshot with neither side is untrustworthy, not worthless.
        return Evaluation.unknown();
      }
      // Bid gone with a live ask: the real bled-to-worthless case — -100%, step 100.
      return new Evaluation(Status.BREACH, BigDecimal.ONE, 100, false);
    }
    BigDecimal floor = entryPremium.multiply(BigDecimal.ONE.subtract(t));
    if (bid.compareTo(floor) <= 0) {
      BigDecimal lossPct = entryPremium.subtract(bid).divide(entryPremium, MathContext.DECIMAL64);
      return new Evaluation(Status.BREACH, lossPct, stepOf(lossPct), false);
    }
    boolean recovered = bid.compareTo(floor.multiply(REARM_BAND)) >= 0;
    return new Evaluation(Status.OK, null, 0, recovered);
  }

  /** 10%-bucketed loss depth: {@code floor(lossPct / 0.10) x 10}, capped at 100. */
  static int stepOf(BigDecimal lossPct) {
    int step = lossPct.multiply(BigDecimal.TEN).setScale(0, RoundingMode.FLOOR).intValue() * 10;
    return Math.min(step, 100);
  }
}
