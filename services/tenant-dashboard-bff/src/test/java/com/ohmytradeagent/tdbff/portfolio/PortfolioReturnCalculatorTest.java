package com.ohmytradeagent.tdbff.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.ohmytradeagent.tdbff.portfolio.PortfolioReturnCalculator.RangeReturn;
import java.math.BigDecimal;
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
            true);

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
            true);

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
            true);

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
            false);

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
            true);

    assertThat(rr.rangePl()).isEqualByComparingTo("1000");
    assertThat(rr.rangePlPct()).isNull();
  }

  @Test
  void emptyEquity_bothNull() {
    RangeReturn rr =
        calc.compute(
            List.of(), new BigDecimal("5000"), List.of(1000L, 3000L), List.of(), List.of(), true);

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
            true);

    assertThat(rr.rangePl()).isNull();
    assertThat(rr.rangePlPct()).isNull();
  }

  @Test
  void outOfWindowFlowsIgnored() {
    // A flow before T0 and one after T1 are ignored; only the in-window +41230 counts.
    RangeReturn rr =
        calc.compute(
            List.of(new BigDecimal("5000.00"), new BigDecimal("52259.56")),
            new BigDecimal("5000.00"),
            List.of(1000L, 3000L),
            List.of(500L, 2000L, 3500L),
            List.of(new BigDecimal("999"), new BigDecimal("41230"), new BigDecimal("777")),
            true);

    assertThat(rr.rangePl()).isEqualByComparingTo("6029.56");
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
            true);

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
            true);

    assertThat(rr.rangePl()).isEqualByComparingTo("400");
    assertThat(rr.rangePlPct().doubleValue()).isCloseTo(0.03755868, within(1e-8));
  }
}
