package com.ohmytradeagent.tdbff.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.ohmytradeagent.tdbff.portfolio.PortfolioReturnCalculator.RangeReturn;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Incident-reproduction unit tests for the deposit-adjusted range return. The live acct 847309116
 * was funded from $5k with a +$41,230 deposit on 07-15; Alpaca's {@code no_reset} mode reported
 * +$47,259 / +945% because it counts the deposit as profit. These tests prove the calculator strips
 * that inflation out and yields a bounded, deposit-free return.
 */
class PortfolioReturnCalculatorTest {

  private final PortfolioReturnCalculator calc = new PortfolioReturnCalculator();

  @Test
  void depositInWindow_stripsDepositInflation() {
    // BV=5000, EV=52259.56, a +41230 deposit at the mid-range timestamp. The naive no-reset return
    // is (52259.56-5000)/5000 = 9.4519 (945%); the deposit-adjusted trading-only P&L is
    // 52259.56-5000-41230 = 6029.56 and the Modified-Dietz % is a bounded low fraction.
    RangeReturn rr =
        calc.compute(
            List.of(new BigDecimal("5000.00"), new BigDecimal("52259.56")),
            new BigDecimal("5000.00"),
            List.of(1000L, 3000L),
            List.of(2000L),
            List.of(new BigDecimal("41230")),
            true,
            null,
            null,
            null);

    // Trading-only $ P&L, deposit removed.
    assertThat(rr.rangePl()).isEqualByComparingTo("6029.56");

    // Modified-Dietz: denom = 5000 + 0.5*41230 = 25615; 6029.56/25615 = 0.2353918...
    assertThat(rr.rangePlPct()).isNotNull();
    assertThat(rr.rangePlPct().doubleValue()).isCloseTo(0.2353918, within(1e-6));
    // Bounded, deposit-free — NOT the 9.45 (945%) no-reset inflation.
    assertThat(rr.rangePlPct().compareTo(BigDecimal.ONE)).isLessThan(0);
    assertThat(rr.rangePlPct().subtract(new BigDecimal("9.45")).abs())
        .isGreaterThan(new BigDecimal("1"));
  }

  @Test
  void rangeDiffersFromToday_whenMidRangeDepositExists() {
    // profit_loss[last] ("Today") would be +4282, but a mid-range +8000 deposit means the range P&L
    // is 30000-20000-8000 = 2000, which is NOT 4282 — range != today.
    RangeReturn rr =
        calc.compute(
            List.of(new BigDecimal("20000"), new BigDecimal("30000")),
            new BigDecimal("20000"),
            List.of(1000L, 3000L),
            List.of(2000L),
            List.of(new BigDecimal("8000")),
            true,
            null,
            null,
            null);

    assertThat(rr.rangePl()).isEqualByComparingTo("2000");
    assertThat(rr.rangePl()).isNotEqualByComparingTo("4282");
  }

  @Test
  void baseValueZero_pctNull_noDivideByZero() {
    // base_value=0 (seen in the 3M payload) with no weighted flows → denom 0 → pct null; the $ P&L
    // is still computable.
    RangeReturn rr =
        calc.compute(
            List.of(new BigDecimal("0"), new BigDecimal("1000")),
            BigDecimal.ZERO,
            List.of(1000L, 3000L),
            List.of(),
            List.of(),
            true,
            null,
            null,
            null);

    assertThat(rr.rangePl()).isEqualByComparingTo("1000");
    assertThat(rr.rangePlPct()).isNull();
  }

  @Test
  void flowsUnavailable_bothNull() {
    RangeReturn rr =
        calc.compute(
            List.of(new BigDecimal("5000"), new BigDecimal("52259.56")),
            new BigDecimal("5000"),
            List.of(1000L, 3000L),
            List.of(2000L),
            List.of(new BigDecimal("41230")),
            false,
            null,
            null,
            null);

    assertThat(rr.rangePl()).isNull();
    assertThat(rr.rangePlPct()).isNull();
  }

  @Test
  void singleTimestampWindow_pctNull() {
    // T1==T0 → weights undefined → pct null; $ P&L still computable.
    RangeReturn rr =
        calc.compute(
            List.of(new BigDecimal("5000"), new BigDecimal("6000")),
            new BigDecimal("5000"),
            List.of(2000L, 2000L),
            List.of(),
            List.of(),
            true,
            null,
            null,
            null);

    assertThat(rr.rangePl()).isEqualByComparingTo("1000");
    assertThat(rr.rangePlPct()).isNull();
  }

  @Test
  void emptyEquity_bothNull() {
    RangeReturn rr =
        calc.compute(
            List.of(),
            new BigDecimal("5000"),
            List.of(1000L, 3000L),
            List.of(),
            List.of(),
            true,
            null,
            null,
            null);

    assertThat(rr.rangePl()).isNull();
    assertThat(rr.rangePlPct()).isNull();
  }

  @Test
  void baseValueNull_bothNull() {
    RangeReturn rr =
        calc.compute(
            List.of(new BigDecimal("5000"), new BigDecimal("6000")),
            null,
            List.of(1000L, 3000L),
            List.of(),
            List.of(),
            true,
            null,
            null,
            null);

    assertThat(rr.rangePl()).isNull();
    assertThat(rr.rangePlPct()).isNull();
  }

  @Test
  void outOfWindowFlowsIgnored() {
    // A flow on a day BEFORE the range and one AFTER T1 are ignored; only the in-window +41230
    // counts. Uses realistic epochs (equity bars at market time, flows at midnight UTC) because the
    // window's lower bound is t0's CALENDAR DAY, not the t0 instant — see the first-day-deposit
    // test above. Synthetic small integers would put every flow inside UTC day 0 and prove nothing.
    long t0 = Instant.parse("2026-07-15T20:00:00Z").getEpochSecond();
    long t1 = Instant.parse("2026-07-17T20:00:00Z").getEpochSecond();

    RangeReturn rr =
        calc.compute(
            List.of(new BigDecimal("5000.00"), new BigDecimal("52259.56")),
            new BigDecimal("5000.00"),
            List.of(t0, t1),
            List.of(
                Instant.parse("2026-07-14T00:00:00Z").getEpochSecond(), // day before the range
                Instant.parse("2026-07-16T00:00:00Z").getEpochSecond(), // in window
                Instant.parse("2026-07-18T00:00:00Z").getEpochSecond()), // after T1
            List.of(new BigDecimal("999"), new BigDecimal("41230"), new BigDecimal("777")),
            true,
            null,
            null,
            null);

    assertThat(rr.rangePl()).isEqualByComparingTo("6029.56");
  }

  @Test
  void depositOnTheRangeFirstDay_isStillNettedOutDespiteMidnightVsMarketTimestamps() {
    // REGRESSION: the two timestamp series have different granularity. Cash flows are parsed from
    // Alpaca's date-only non-trade activities → MIDNIGHT UTC. Equity bars are MARKET time. So a
    // deposit made on the range's FIRST day arrives with t < t0 and an exact `t < t0` window filter
    // silently dropped it — re-inflating the range by the full deposit, i.e. the +945% class of bug
    // this whole calculator exists to remove, narrowed to ranges that start on a transfer day.
    // Real epochs: 2026-07-15 20:00Z .. 2026-07-17 20:00Z, deposit dated 2026-07-15 → 00:00Z.
    long t0 = Instant.parse("2026-07-15T20:00:00Z").getEpochSecond();
    long t1 = Instant.parse("2026-07-17T20:00:00Z").getEpochSecond();
    long depositTs = Instant.parse("2026-07-15T00:00:00Z").getEpochSecond();
    assertThat(depositTs).isLessThan(t0); // the exact condition that used to drop the flow

    RangeReturn rr =
        calc.compute(
            List.of(new BigDecimal("5000.00"), new BigDecimal("52259.56")),
            new BigDecimal("5000.00"),
            List.of(t0, t1),
            List.of(depositTs),
            List.of(new BigDecimal("41230")),
            true,
            null,
            null,
            null);

    // Deposit netted out → trading-only P&L, NOT 47259.56.
    assertThat(rr.rangePl()).isEqualByComparingTo("6029.56");
    // Weight clamps to 1 (money present for the whole window) → denom = 5000 + 41230 = 46230.
    assertThat(rr.rangePlPct().doubleValue()).isCloseTo(6029.56 / 46230.0, within(1e-9));
    // Sanity: nowhere near the 9.45 (945%) inflation the dropped-flow path produced.
    assertThat(rr.rangePlPct().compareTo(BigDecimal.ONE)).isLessThan(0);
  }

  @Test
  void flowOnTheDayBeforeTheRange_isStillExcluded() {
    // The lower bound is floored to t0's UTC DAY, not widened arbitrarily: a deposit dated the
    // previous calendar day is already inside base_value and must stay out, or it would be
    // subtracted twice and understate the return.
    long t0 = Instant.parse("2026-07-15T20:00:00Z").getEpochSecond();
    long t1 = Instant.parse("2026-07-17T20:00:00Z").getEpochSecond();
    long priorDay = Instant.parse("2026-07-14T00:00:00Z").getEpochSecond();

    RangeReturn rr =
        calc.compute(
            List.of(new BigDecimal("5000.00"), new BigDecimal("6000.00")),
            new BigDecimal("5000.00"),
            List.of(t0, t1),
            List.of(priorDay),
            List.of(new BigDecimal("41230")),
            true,
            null,
            null,
            null);

    assertThat(rr.rangePl()).isEqualByComparingTo("1000.00");
  }

  @Test
  void withdrawalInWindow_addsBackToTradingPlAndShrinksDenominator() {
    // A withdrawal is a NEGATIVE flow: equity fell 20000→17000 but 5000 of that walked out the
    // door, so trading P&L is 17000-20000-(-5000) = +2000, a GAIN, not a 3000 loss. Modified-Dietz
    // denom = 20000 + 0.5*(-5000) = 17500 → 2000/17500 = 0.1142857...
    RangeReturn rr =
        calc.compute(
            List.of(new BigDecimal("20000"), new BigDecimal("17000")),
            new BigDecimal("20000"),
            List.of(1000L, 3000L),
            List.of(2000L),
            List.of(new BigDecimal("-5000")),
            true,
            null,
            null,
            null);

    assertThat(rr.rangePl()).isEqualByComparingTo("2000");
    assertThat(rr.rangePlPct().doubleValue()).isCloseTo(0.1142857, within(1e-6));
  }

  @Test
  void multipleFlowsAtDifferentTimes_weightedByTimeRemaining() {
    // Two flows at DIFFERENT weights: T0=0, T1=1000. A +1000 deposit at t=250 carries weight
    // (1000-250)/1000 = 0.75; a −400 withdrawal at t=750 carries weight 0.25. Net flows = +600, so
    // rangePl = 11000-10000-600 = 400. Denominator = 10000 + (0.75*1000) + (0.25*-400) = 10650 →
    // 400/10650 = 0.03755868... A naive equal-weighting (0.5 each) would give 10300 → 0.038835,
    // so this pins the time-weighting, not just the net.
    RangeReturn rr =
        calc.compute(
            List.of(new BigDecimal("10000"), new BigDecimal("11000")),
            new BigDecimal("10000"),
            List.of(0L, 1000L),
            List.of(250L, 750L),
            List.of(new BigDecimal("1000"), new BigDecimal("-400")),
            true,
            null,
            null,
            null);

    assertThat(rr.rangePl()).isEqualByComparingTo("400");
    assertThat(rr.rangePlPct().doubleValue()).isCloseTo(0.03755868, within(1e-8));
  }

  @Test
  void baseValueAsof_excludesInitialFundingBakedIntoBase_prodRealIncident() {
    // INCIDENT REPRODUCTION (prod_real, real money, 2026-07-22). 1M range: base_value=5000 asof
    // 2026-06-18, equity[last]=52259.56. Deposits: 06-12:+5000 (the INITIAL FUNDING, already in
    // base_value), 07-06:+3000, 07-13:+1000, 07-15:+40000. The leading equity timestamp is a
    // pre-funding degenerate slot (0L) — the exact production shape that floored windowStart to 0
    // and admitted the 06-12 funding as an in-window flow, subtracting it TWICE.
    long asof = Instant.parse("2026-06-18T00:00:00Z").getEpochSecond();
    long t1 = Instant.parse("2026-07-22T20:00:00Z").getEpochSecond();
    List<BigDecimal> equity = List.of(new BigDecimal("5000.00"), new BigDecimal("52259.56"));
    BigDecimal baseValue = new BigDecimal("5000.00");
    List<Long> timestamps = List.of(0L, t1); // production shape: leading pre-funding slot
    List<Long> flowTs =
        List.of(
            Instant.parse("2026-06-12T00:00:00Z").getEpochSecond(), // initial funding (<= asof)
            Instant.parse("2026-07-06T00:00:00Z").getEpochSecond(),
            Instant.parse("2026-07-13T00:00:00Z").getEpochSecond(),
            Instant.parse("2026-07-15T00:00:00Z").getEpochSecond());
    List<BigDecimal> flowAmt =
        List.of(
            new BigDecimal("5000"),
            new BigDecimal("3000"),
            new BigDecimal("1000"),
            new BigDecimal("40000"));

    // BEFORE (baseValueAsof=null): the 0L window admits the 06-12 funding → all $49,000 subtracted
    // →
    // 52259.56 - 5000 - 49000 = -1740.44, a FAKE LOSS. This is the shipped bug.
    RangeReturn buggy =
        calc.compute(equity, baseValue, timestamps, flowTs, flowAmt, true, null, null, null);
    assertThat(buggy.rangePl()).isEqualByComparingTo("-1740.44");

    // AFTER (baseValueAsof=2026-06-18): the 06-12 funding is at/before the as-of and is excluded
    // (already in base_value) → 52259.56 - 5000 - 44000 = +3259.56, the TRUE profit.
    RangeReturn fixed =
        calc.compute(equity, baseValue, timestamps, flowTs, flowAmt, true, asof, null, null);
    assertThat(fixed.rangePl()).isEqualByComparingTo("3259.56");
    assertThat(fixed.rangePlPct()).isNotNull();
  }

  @Test
  void baseValueAsof_boundary_excludesFlowOnAsofDay_includesNextDay() {
    // A flow dated EXACTLY on base_value_asof is already baked into base_value → EXCLUDED. The next
    // calendar day is a fresh in-window flow → INCLUDED.
    long asof = Instant.parse("2026-06-18T00:00:00Z").getEpochSecond();
    long t1 = Instant.parse("2026-06-30T20:00:00Z").getEpochSecond();
    List<BigDecimal> equity = List.of(new BigDecimal("5000.00"), new BigDecimal("10000.00"));
    BigDecimal baseValue = new BigDecimal("5000.00");
    List<Long> timestamps = List.of(0L, t1);

    // Flow exactly ON the as-of day → excluded: rangePl = 10000 - 5000 - 0 = 5000.
    RangeReturn onAsof =
        calc.compute(
            equity,
            baseValue,
            timestamps,
            List.of(asof),
            List.of(new BigDecimal("2000")),
            true,
            asof,
            null,
            null);
    assertThat(onAsof.rangePl()).isEqualByComparingTo("5000.00");

    // Flow the NEXT day → included: rangePl = 10000 - 5000 - 2000 = 3000.
    long nextDay = Instant.parse("2026-06-19T00:00:00Z").getEpochSecond();
    RangeReturn nextDayFlow =
        calc.compute(
            equity,
            baseValue,
            timestamps,
            List.of(nextDay),
            List.of(new BigDecimal("2000")),
            true,
            asof,
            null,
            null);
    assertThat(nextDayFlow.rangePl()).isEqualByComparingTo("3000.00");
  }

  @Test
  void liveEquity_usedAsEvOverStaleDailyBar_prodKiparkReproduction() {
    // INCIDENT REPRODUCTION (prod-kipark, acct 310056593, funded a clean $50,000). A daily-bar 1M
    // range: base_value=50000 asof 2026-07-20, equity[last]=54360.02 is the LAST COMPLETED SESSION
    // (yesterday's close). The chart's last point overstates the range by today's intraday move.
    // Live account equity is 52577.52 — the SAME value the /live header total shows. With no
    // in-window flows the true range = 52577.52 - 50000 = +2577.52 (exactly what 1W already shows,
    // since 1W's intraday last bar IS the live EV). The stale-bar EV would wrongly show +4360.02.
    long asof = Instant.parse("2026-07-20T00:00:00Z").getEpochSecond();
    long t1 = Instant.parse("2026-07-22T20:00:00Z").getEpochSecond();
    long evAsOf = Instant.parse("2026-07-22T21:00:00Z").getEpochSecond(); // NOW, after t1
    List<BigDecimal> equity = List.of(new BigDecimal("50000.00"), new BigDecimal("54360.02"));
    BigDecimal baseValue = new BigDecimal("50000.00");
    List<Long> timestamps = List.of(0L, t1);

    // BEFORE (liveEquity=null): EV = equity[last] = 54360.02 (yesterday's close) → 54360.02 - 50000
    // = +4360.02, overstated by today's -1782.50 move.
    RangeReturn staleBar =
        calc.compute(equity, baseValue, timestamps, List.of(), List.of(), true, asof, null, null);
    assertThat(staleBar.rangePl()).isEqualByComparingTo("4360.02");

    // AFTER (liveEquity=52577.52): EV = live equity → 52577.52 - 50000 = +2577.52, the TRUE range,
    // matching 1W and reconciling with total − funded. The flow window is empty so the NOW evAsOf
    // upper bound is inert here — rangePl is UNCHANGED at 2577.52.
    RangeReturn live =
        calc.compute(
            equity,
            baseValue,
            timestamps,
            List.of(),
            List.of(),
            true,
            asof,
            new BigDecimal("52577.52"),
            evAsOf);
    assertThat(live.rangePl()).isEqualByComparingTo("2577.52");
    // Modified-Dietz % over a flow-free window = rangePl / base_value = 2577.52 / 50000.
    assertThat(live.rangePlPct().doubleValue()).isCloseTo(2577.52 / 50000.0, within(1e-9));
  }

  @Test
  void sameDayFlowWithLiveEquity_subtractedNotCountedAsProfit() {
    // SAME-DAY DEPOSIT reconciliation (the #615 correctness gap). Daily-bar 1M range:
    // base_value=50000 asof before the series, equity[last]=54360.02 is YESTERDAY's close, and a
    // $10,000 deposit lands TODAY (timestamp AFTER timestamps[last]). Live equity = 64360.02
    // INCLUDES that deposit. Correct rangePl = liveEquity − base − netFlows = 64360.02 − 50000 −
    // 10000 = 4360.02 = total(64360.02) − funded(60000). The coupling: the flow window's upper
    // bound must follow the EV's as-of time (NOW), or the today-dated deposit is dropped as
    // "after the series" and rides into EV − BV as fake profit.
    long asof = Instant.parse("2026-07-19T00:00:00Z").getEpochSecond();
    long t1 = Instant.parse("2026-07-21T20:00:00Z").getEpochSecond(); // yesterday's close
    long todayDeposit = Instant.parse("2026-07-22T14:00:00Z").getEpochSecond(); // after t1
    long evAsOf = Instant.parse("2026-07-22T20:00:00Z").getEpochSecond(); // NOW, after the deposit
    assertThat(todayDeposit).isGreaterThan(t1);
    assertThat(evAsOf).isGreaterThan(todayDeposit);

    List<BigDecimal> equity = List.of(new BigDecimal("50000.00"), new BigDecimal("54360.02"));
    BigDecimal baseValue = new BigDecimal("50000.00");
    List<Long> timestamps = List.of(0L, t1);
    List<Long> flowTs = List.of(todayDeposit);
    List<BigDecimal> flowAmt = List.of(new BigDecimal("10000"));

    // WITHOUT the coupling (evAsOf = the old timestamps[last]=t1): the today-flow is > t1 → dropped
    // as out-of-window, so the $10k deposit rides in the live EV as FAKE PROFIT → 14360.02.
    RangeReturn uncoupled =
        calc.compute(
            equity,
            baseValue,
            timestamps,
            flowTs,
            flowAmt,
            true,
            asof,
            new BigDecimal("64360.02"),
            t1);
    assertThat(uncoupled.rangePl()).isEqualByComparingTo("14360.02");

    // WITH the fix (evAsOf after the today-flow): the window extends to the EV's as-of time → the
    // deposit is netted out → 64360.02 − 50000 − 10000 = 4360.02 = total − funded.
    RangeReturn fixed =
        calc.compute(
            equity,
            baseValue,
            timestamps,
            flowTs,
            flowAmt,
            true,
            asof,
            new BigDecimal("64360.02"),
            evAsOf);
    assertThat(fixed.rangePl()).isEqualByComparingTo("4360.02");
  }

  @Test
  void liveEquityNull_fallsBackToEquityLast() {
    // Behavior-preserving: with no live-equity snapshot the EV stays the series' last point.
    long asof = Instant.parse("2026-07-20T00:00:00Z").getEpochSecond();
    long t1 = Instant.parse("2026-07-22T20:00:00Z").getEpochSecond();
    RangeReturn rr =
        calc.compute(
            List.of(new BigDecimal("50000.00"), new BigDecimal("54360.02")),
            new BigDecimal("50000.00"),
            List.of(0L, t1),
            List.of(),
            List.of(),
            true,
            asof,
            null,
            null);
    assertThat(rr.rangePl()).isEqualByComparingTo("4360.02");
  }

  // ---------------------------------------------------------------------------------------------
  // Modified-Dietz weighting anchor (the divergent-% fix). The money-weighting window start (T0)
  // must be base_value_asof — the SAME anchor the flow-exclusion uses — not the padded series
  // start.
  // ---------------------------------------------------------------------------------------------

  // Shared inputs for the anchor tests: a $44k deposit 10 days before the series end, base_value
  // 5000 valued at inception, EV 51,439.49 → trading-only $ P&L = 51439.49 − 5000 − 44000 =
  // 2439.49.
  private static final long INCEPTION = Instant.parse("2026-06-25T00:00:00Z").getEpochSecond();
  private static final long SERIES_END = Instant.parse("2026-07-25T20:00:00Z").getEpochSecond();
  private static final long DEPOSIT_TS = Instant.parse("2026-07-15T00:00:00Z").getEpochSecond();
  private static final List<BigDecimal> EQUITY_2439 =
      List.of(new BigDecimal("5000.00"), new BigDecimal("51439.49"));
  private static final BigDecimal BV_5000 = new BigDecimal("5000.00");
  private static final List<Long> DEP_TS = List.of(DEPOSIT_TS);
  private static final List<BigDecimal> DEP_AMT = List.of(new BigDecimal("44000"));

  @Test
  void anchoredToBaseValueAsof_pctIndependentOfHowFarAlpacaPaddedTheSeries() {
    // THE FIX. Two ranges, identical in everything a return should depend on (same baseline as-of,
    // same BV, same EV, same deposit, same window END) — differing ONLY in how far back Alpaca
    // padded
    // the equity series' FIRST timestamp: one starts at inception, one is padded ~11 months earlier
    // (the 1Y-on-a-1-month-old-account shape). With T0 anchored to base_value_asof, the padded
    // start
    // is irrelevant → identical $ AND identical %.
    long paddedStart = Instant.parse("2025-08-01T00:00:00Z").getEpochSecond();

    RangeReturn atInception =
        calc.compute(
            EQUITY_2439,
            BV_5000,
            List.of(INCEPTION, SERIES_END),
            DEP_TS,
            DEP_AMT,
            true,
            INCEPTION,
            null,
            null);
    RangeReturn paddedBack =
        calc.compute(
            EQUITY_2439,
            BV_5000,
            List.of(paddedStart, SERIES_END), // series padded ~11 months before funding
            DEP_TS,
            DEP_AMT,
            true,
            INCEPTION,
            null,
            null);

    assertThat(atInception.rangePl()).isEqualByComparingTo("2439.49");
    assertThat(paddedBack.rangePl()).isEqualByComparingTo("2439.49");
    // The point of the fix: the % does NOT move when the series start is padded further back.
    assertThat(paddedBack.rangePlPct()).isEqualByComparingTo(atInception.rangePlPct());
    // And it is a bounded, sane trading return — nowhere near the inflated legacy value below.
    assertThat(atInception.rangePlPct().doubleValue()).isBetween(0.10, 0.15);
  }

  @Test
  void legacyPath_noBaseValueAsof_paddedSeriesStartDistortsPct_whyTheAnchorIsNeeded() {
    // REGRESSION DOC: on the base_value_asof-absent fallback path, T0 = timestamps[0], so padding
    // the
    // series start further back DOES distort the % (shrinks the deposit weight → shrinks the
    // denominator → inflates the %) even though the $ is unchanged. This is the exact bug the
    // anchor
    // above removes; kept as a guard so nobody "simplifies" the anchor away without noticing.
    long paddedStart = Instant.parse("2025-08-01T00:00:00Z").getEpochSecond();

    RangeReturn nearStart =
        calc.compute(
            EQUITY_2439,
            BV_5000,
            List.of(INCEPTION, SERIES_END),
            DEP_TS,
            DEP_AMT,
            true,
            null,
            null,
            null);
    RangeReturn paddedStartRr =
        calc.compute(
            EQUITY_2439,
            BV_5000,
            List.of(paddedStart, SERIES_END),
            DEP_TS,
            DEP_AMT,
            true,
            null,
            null,
            null);

    // Same dollars…
    assertThat(nearStart.rangePl()).isEqualByComparingTo("2439.49");
    assertThat(paddedStartRr.rangePl()).isEqualByComparingTo("2439.49");
    // …but a padded start inflates the % on this legacy path (the bug): padded > near.
    assertThat(paddedStartRr.rangePlPct()).isGreaterThan(nearStart.rangePlPct());
  }

  @Test
  void rangesSharingInceptionBaseline_sameDollarsAndSamePct_userIncidentReproduction() {
    // USER INCIDENT (2026-07-25): 1M/3M/YTD/1Y all showed the SAME $ but a % that climbed with
    // range
    // length (6.80 → 13.85 → 19.38 → 22.66%). Reproduces the daily-bar shape: EV is the live
    // account
    // snapshot valued at NOW (evAsOf), base_value clamps to the inception baseline for every range
    // ≥
    // account age, and the ranges differ only in the padded series start. Post-fix: identical $ AND
    // identical % across all of them.
    long now = Instant.parse("2026-07-25T21:00:00Z").getEpochSecond();
    BigDecimal liveEquity = new BigDecimal("51439.49");
    // 1M/3M/1Y series each start progressively earlier; base_value_asof is the shared inception.
    List<Long> oneMonthStart = List.of(INCEPTION, SERIES_END);
    List<Long> threeMonthStart = List.of(INCEPTION - Duration.ofDays(60).getSeconds(), SERIES_END);
    List<Long> oneYearStart = List.of(INCEPTION - Duration.ofDays(335).getSeconds(), SERIES_END);

    RangeReturn m1 =
        calc.compute(
            EQUITY_2439, BV_5000, oneMonthStart, DEP_TS, DEP_AMT, true, INCEPTION, liveEquity, now);
    RangeReturn m3 =
        calc.compute(
            EQUITY_2439,
            BV_5000,
            threeMonthStart,
            DEP_TS,
            DEP_AMT,
            true,
            INCEPTION,
            liveEquity,
            now);
    RangeReturn y1 =
        calc.compute(
            EQUITY_2439, BV_5000, oneYearStart, DEP_TS, DEP_AMT, true, INCEPTION, liveEquity, now);

    // Same dollars (already true before the fix)…
    assertThat(m1.rangePl()).isEqualByComparingTo("2439.49");
    assertThat(m3.rangePl()).isEqualByComparingTo("2439.49");
    assertThat(y1.rangePl()).isEqualByComparingTo("2439.49");
    // …and now the SAME percentage too — the divergence is gone.
    assertThat(m3.rangePlPct()).isEqualByComparingTo(m1.rangePlPct());
    assertThat(y1.rangePlPct()).isEqualByComparingTo(m1.rangePlPct());
  }

  @Test
  void depositJustAfterAsof_weightNearOne_denominatorIsFullInvestedCapital() {
    // A deposit dated one day after base_value_asof, over a 40-day window, was in the account for
    // 39/40 of the window → weight 0.975, so the denominator is essentially BV + the full deposit
    // (the money's real average presence), NOT a tiny weighted sliver. Pins the intended semantics.
    long asof = Instant.parse("2026-06-15T00:00:00Z").getEpochSecond();
    long t1 = Instant.parse("2026-07-25T00:00:00Z").getEpochSecond(); // 40 days after asof
    long deposit = Instant.parse("2026-06-16T00:00:00Z").getEpochSecond(); // 1 day after asof
    // EV 50,439.49 − BV 5000 − deposit 44000 = 1439.49.
    RangeReturn rr =
        calc.compute(
            List.of(new BigDecimal("5000.00"), new BigDecimal("50439.49")),
            BV_5000,
            List.of(asof, t1),
            List.of(deposit),
            List.of(new BigDecimal("44000")),
            true,
            asof,
            null,
            null);

    assertThat(rr.rangePl()).isEqualByComparingTo("1439.49");
    // denominator = 5000 + (39/40)*44000; expected computed from the same formula (no magic
    // decimal).
    double expected = 1439.49 / (5000.0 + (39.0 / 40.0) * 44000.0);
    assertThat(rr.rangePlPct().doubleValue()).isCloseTo(expected, within(1e-9));
  }

  @Test
  void baseValueAsofAfterSeriesEnd_spanNonPositive_pctNull_dollarsStillComputed() {
    // Degenerate: base_value_asof dated AFTER the window end → no positive span → pct null (never a
    // divide-by-zero or a bogus number), while the $ P&L stays computable.
    long t1 = Instant.parse("2026-07-25T20:00:00Z").getEpochSecond();
    long asofAfter = Instant.parse("2026-08-01T00:00:00Z").getEpochSecond();
    RangeReturn rr =
        calc.compute(
            List.of(new BigDecimal("5000.00"), new BigDecimal("6000.00")),
            BV_5000,
            List.of(INCEPTION, t1),
            List.of(),
            List.of(),
            true,
            asofAfter,
            null,
            null);

    assertThat(rr.rangePl()).isEqualByComparingTo("1000.00");
    assertThat(rr.rangePlPct()).isNull();
  }

  @Test
  void baseValueAsofEqualsSeriesEnd_spanZero_pctNull() {
    // base_value_asof exactly at the window end → span 0 → pct null; $ still computed.
    long t1 = Instant.parse("2026-07-25T20:00:00Z").getEpochSecond();
    RangeReturn rr =
        calc.compute(
            List.of(new BigDecimal("5000.00"), new BigDecimal("6000.00")),
            BV_5000,
            List.of(INCEPTION, t1),
            List.of(),
            List.of(),
            true,
            t1,
            null,
            null);

    assertThat(rr.rangePl()).isEqualByComparingTo("1000.00");
    assertThat(rr.rangePlPct()).isNull();
  }
}
