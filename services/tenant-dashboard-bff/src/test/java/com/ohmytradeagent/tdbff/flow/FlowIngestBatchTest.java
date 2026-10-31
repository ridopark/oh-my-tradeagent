package com.ohmytradeagent.tdbff.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ohmytradeagent.tdbff.flow.FlowRepository.Order;
import com.ohmytradeagent.tdbff.flow.FlowRepository.TickerDay;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FlowIngestBatch#validate()} NUMERIC bounds (finding 4): out-of-range values
 * must be rejected with 400 before reaching Postgres (22003 → 500 on the whole batch).
 */
class FlowIngestBatchTest {

  // ---------------------------------------------------------------------------
  // fits() helper contract
  // ---------------------------------------------------------------------------

  @Test
  void fits_null_isAlwaysTrue() {
    assertThat(FlowIngestBatch.fits(null, 8, 4)).isTrue();
  }

  @Test
  void fits_zero_isTrue() {
    assertThat(FlowIngestBatch.fits(BigDecimal.ZERO, 8, 4)).isTrue();
  }

  @Test
  void fits_maxValue_isTrue() {
    // NUMERIC(8,4): max int digits = 4 → 9999.9999 fits
    assertThat(FlowIngestBatch.fits(new BigDecimal("9999.9999"), 8, 4)).isTrue();
  }

  @Test
  void fits_oneOverMax_isFalse() {
    // 10000 has 5 integer digits > 4 allowed by NUMERIC(8,4)
    assertThat(FlowIngestBatch.fits(new BigDecimal("10000"), 8, 4)).isFalse();
  }

  // ---------------------------------------------------------------------------
  // moneyness_pct: NUMERIC(8,4)  — finding 4 named examples
  // ---------------------------------------------------------------------------

  @Test
  void moneynessPct_10000_exceedsBound_throwsIAE() {
    Order o =
        orderWith(
            new BigDecimal("200.00"),
            new BigDecimal("1000.00"),
            null,
            null,
            null,
            new BigDecimal("10000"),
            null);
    assertThatThrownBy(() -> new FlowIngestBatch(List.of(o), List.of()).validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("order.moneyness_pct");
  }

  @Test
  void moneynessPct_9999_9999_fits() {
    Order o =
        orderWith(
            new BigDecimal("200.00"),
            new BigDecimal("1000.00"),
            null,
            null,
            null,
            new BigDecimal("9999.9999"),
            null);
    assertThatCode(() -> new FlowIngestBatch(List.of(o), List.of()).validate())
        .doesNotThrowAnyException();
  }

  // ---------------------------------------------------------------------------
  // strike: NUMERIC(12,4) — finding 4 requires one 12,4 test
  // ---------------------------------------------------------------------------

  @Test
  void strike_overBound_throwsIAE() {
    // NUMERIC(12,4): max int digits = 8 → 100000000 (9 int digits) overflows
    Order o =
        orderWith(
            new BigDecimal("100000000.00"),
            new BigDecimal("1000.00"),
            null,
            null,
            null,
            null,
            null);
    assertThatThrownBy(() -> new FlowIngestBatch(List.of(o), List.of()).validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("order.strike");
  }

  // ---------------------------------------------------------------------------
  // premium: NUMERIC(16,2) — finding 4 requires one 16,2 test
  // ---------------------------------------------------------------------------

  @Test
  void premium_overBound_throwsIAE() {
    // NUMERIC(16,2): max int digits = 14 → 100000000000000 (15 int digits) overflows
    Order o =
        orderWith(
            new BigDecimal("200.00"),
            new BigDecimal("100000000000000.00"),
            null,
            null,
            null,
            null,
            null);
    assertThatThrownBy(() -> new FlowIngestBatch(List.of(o), List.of()).validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("order.premium");
  }

  @Test
  void fits_valueThatRoundsUpIntoOverflow_isFalse() {
    // Postgres rounds 9999.99995 to 10000.0000 on insert into NUMERIC(8,4): overflow (22003).
    assertThat(FlowIngestBatch.fits(new BigDecimal("9999.99995"), 8, 4)).isFalse();
    assertThat(FlowIngestBatch.fits(new BigDecimal("9999.99994"), 8, 4)).isTrue();
    assertThat(FlowIngestBatch.fits(new BigDecimal("-9999.99995"), 8, 4)).isFalse();
  }

  @Test
  void tickerDay_callPremium_overBound_throwsIAE() {
    TickerDay t = tickerDay(new BigDecimal("10000000000000000"));
    assertThatThrownBy(() -> new FlowIngestBatch(List.of(), List.of(t)).validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ticker_day.call_premium");
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Constructs a fully valid {@link Order} overriding the given fields. A null strike, premium or
   * blockPremiumThreshold uses the base-fixture value; a null bid, ask, underlyingPrice or
   * moneynessPct stays null (stored as SQL NULL). Parameters: strike, premium, bid, ask,
   * underlyingPrice, moneynessPct, blockPremiumThreshold.
   */
  private static Order orderWith(
      BigDecimal strike,
      BigDecimal premium,
      BigDecimal bid,
      BigDecimal ask,
      BigDecimal underlyingPrice,
      BigDecimal moneynessPct,
      BigDecimal blockPremiumThreshold) {
    return new Order(
        "test-id",
        "AAPL",
        "AAPL240119C00200000",
        LocalDate.of(2024, 1, 19),
        strike != null ? strike : new BigDecimal("200.00"),
        "C",
        OffsetDateTime.parse("2026-10-31T14:00:00Z"),
        OffsetDateTime.parse("2026-10-31T14:00:00Z"),
        1,
        1,
        1,
        premium != null ? premium : new BigDecimal("1000.00"),
        new BigDecimal("1.0000"),
        "buy",
        "quote",
        bid,
        ask,
        OffsetDateTime.parse("2026-10-31T13:59:59Z"),
        false,
        false,
        false,
        false,
        false,
        null,
        null,
        null,
        underlyingPrice,
        moneynessPct,
        1,
        null,
        blockPremiumThreshold != null ? blockPremiumThreshold : new BigDecimal("250000.00"),
        100,
        100,
        1000);
  }

  private static TickerDay tickerDay(BigDecimal callPremium) {
    return new TickerDay(
        "AAPL",
        LocalDate.of(2026, 10, 31),
        callPremium,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        1,
        1,
        null);
  }
}
