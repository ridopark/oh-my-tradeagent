package com.ohmytradeagent.orchestrator.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class OccSymbolTest {

  @Test
  void integerStrike_padsRootAndStrike() {
    OccSymbol s = OccSymbol.of("NVDA", LocalDate.of(2026, 5, 16), new BigDecimal("140"), "C");

    assertThat(s.value()).isEqualTo("NVDA  260516C00140000");
  }

  @Test
  void fractionalStrike_encodesAsMillis() {
    OccSymbol s = OccSymbol.of("SPY", LocalDate.of(2026, 12, 31), new BigDecimal("140.5"), "P");

    assertThat(s.value()).isEqualTo("SPY   261231P00140500");
  }

  @Test
  void shortRoot_padsWithTrailingSpaces() {
    OccSymbol s = OccSymbol.of("F", LocalDate.of(2026, 1, 9), new BigDecimal("12"), "C");

    assertThat(s.value()).isEqualTo("F     260109C00012000");
  }

  @Test
  void rootLowercase_uppercased() {
    OccSymbol s = OccSymbol.of("nvda", LocalDate.of(2026, 5, 16), new BigDecimal("140"), "C");

    assertThat(s.value()).startsWith("NVDA");
  }

  @Test
  void invalidRight_throws() {
    assertThatThrownBy(
            () -> OccSymbol.of("NVDA", LocalDate.of(2026, 5, 16), new BigDecimal("140"), "X"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("right");
  }

  @Test
  void rootTooLong_throws() {
    assertThatThrownBy(
            () -> OccSymbol.of("LONGSYM", LocalDate.of(2026, 5, 16), new BigDecimal("140"), "C"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("root");
  }

  @Test
  void blankRoot_throws() {
    assertThatThrownBy(
            () -> OccSymbol.of("", LocalDate.of(2026, 5, 16), new BigDecimal("140"), "C"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void zeroStrike_throws() {
    assertThatThrownBy(() -> OccSymbol.of("NVDA", LocalDate.of(2026, 5, 16), BigDecimal.ZERO, "C"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("strike");
  }

  @Test
  void overflowStrike_throws() {
    // 100000.000 -> strike_milli=100_000_000 which is exactly 9 digits, overflow
    assertThatThrownBy(
            () -> OccSymbol.of("NVDA", LocalDate.of(2026, 5, 16), new BigDecimal("100000"), "C"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("overflows");
  }

  @Test
  void finerPrecision_throws() {
    assertThatThrownBy(
            () -> OccSymbol.of("NVDA", LocalDate.of(2026, 5, 16), new BigDecimal("140.0001"), "C"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("precision");
  }

  @Test
  void compact_stripsRootPadding() {
    assertThat(OccSymbol.compact("UNH   260618C00400000")).isEqualTo("UNH260618C00400000");
  }

  @Test
  void compact_nullSafe() {
    assertThat(OccSymbol.compact(null)).isNull();
  }

  @Test
  void compact_isIdempotentOnAlreadyCompact() {
    assertThat(OccSymbol.compact("UNH260618C00400000")).isEqualTo("UNH260618C00400000");
  }

  @Test
  void underlying_extractsRootFromPaddedOcc() {
    assertThat(OccSymbol.underlying("NVDA  260516C00140000")).isEqualTo("NVDA");
    assertThat(OccSymbol.underlying("F     260109C00012000")).isEqualTo("F");
  }

  @Test
  void underlying_extractsRootFromCompactOcc() {
    assertThat(OccSymbol.underlying("NVDA260516C00140000")).isEqualTo("NVDA");
    assertThat(OccSymbol.underlying("UNH260618C00400000")).isEqualTo("UNH");
  }

  @Test
  void underlying_nullSafe() {
    assertThat(OccSymbol.underlying(null)).isNull();
  }
}
