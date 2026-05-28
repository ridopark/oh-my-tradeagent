package com.ohmytradeagent.orchestrator.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link OptionTick#round(BigDecimal)} — the single shared penny-tick rounding
 * helper used by both the entry ({@link BtoPricing#computeBtoLimit}) and exit ({@code
 * PositionWorkflowImpl.exitIntent}) limit paths (Issue #266 Gap A).
 *
 * <p>Determinism note: the helper is pure {@link BigDecimal#setScale(int, java.math.RoundingMode)}
 * math (no clock/IO/randomness) so it is safe to call from Temporal workflow code; results replay
 * identically across JDK versions. Tests compare via {@code compareTo == 0} to avoid flaking on
 * scale mismatches (e.g. {@code 3.15} vs {@code 3.150}).
 */
class OptionTickTest {

  @Test
  void roundsThreeDecimalUp_halfUp_3255to326() {
    // The live #263 / #266 hazard: a 3-dp limit (3.255) draws a non-retryable Alpaca 422 ("limit
    // price must be limited to 2 decimal places"). HALF_UP -> 3.26.
    assertThat(OptionTick.round(new BigDecimal("3.255")))
        .isEqualByComparingTo(new BigDecimal("3.26"));
  }

  @Test
  void roundsThreeDecimalUp_halfUp_1485to149() {
    // #263 PLUG case: 1.35 * 1.10 = 1.485 -> 1.49.
    assertThat(OptionTick.round(new BigDecimal("1.485")))
        .isEqualByComparingTo(new BigDecimal("1.49"));
  }

  @Test
  void alreadyTwoDecimal_isUnchangedValue() {
    assertThat(OptionTick.round(new BigDecimal("3.10")))
        .isEqualByComparingTo(new BigDecimal("3.10"));
  }

  @Test
  void resultScaleIsExactlyTwo() {
    assertThat(OptionTick.round(new BigDecimal("3.255")).scale()).isEqualTo(2);
    assertThat(OptionTick.round(new BigDecimal("3.1")).scale()).isEqualTo(2);
    assertThat(OptionTick.round(new BigDecimal("3")).scale()).isEqualTo(2);
  }

  @Test
  void nullLimit_returnsNull_marketOrderSemantics() {
    // A null limit (MARKET-order flatten) must pass through untouched — there is no price to round.
    assertThat(OptionTick.round(null)).isNull();
  }
}
