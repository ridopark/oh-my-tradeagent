package com.ohmytradeagent.contract.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * OCC → Yahoo Finance link helper. The href carries the COMPACT (space-stripped) OCC — the symbol
 * form Yahoo's quote URL expects; the display text is human-readable; malformed/absent input
 * degrades to plain text (never throws — the #295/#297 non-blocking rule).
 */
class YahooOptionLinkTest {

  @Test
  void operatorExampleNflxSept18_2026_100Call() {
    // Plan's locked example: NFLX Sept 18 2026 $100 Call.
    String md = YahooOptionLink.markdown("NFLX  260918C00100000");
    assertThat(md)
        .isEqualTo(
            "[NFLX 260918C00100000]" + "(https://finance.yahoo.com/quote/NFLX260918C00100000/)");
  }

  @Test
  void hrefIsCompactWhileDisplayIsReadable() {
    // The href is the compact (no-space) OCC Yahoo expects; the display stays single-spaced.
    String md = YahooOptionLink.markdown("AAPL260116C00200000");
    assertThat(md)
        .isEqualTo(
            "[AAPL 260116C00200000]" + "(https://finance.yahoo.com/quote/AAPL260116C00200000/)");
  }

  @Test
  void putRightIsPreserved() {
    String md = YahooOptionLink.markdown("TSLA260116P00100000");
    assertThat(md).contains("quote/TSLA260116P00100000/");
    assertThat(md).contains("[TSLA 260116P00100000]");
  }

  @Test
  void paddedRootIsStrippedInHref() {
    // A padded OCC (root < 6 chars) must yield a compact, space-free Yahoo href.
    String md = YahooOptionLink.markdown("ABCDEF260116C00100000");
    assertThat(md).contains("quote/ABCDEF260116C00100000/");
    assertThat(md).contains("[ABCDEF 260116C00100000]");
    assertThat(md).doesNotContain("%20");
  }

  @Test
  void fromPartsBuildsOccWithStrikeTimes1000AndPadding() {
    // TSLA, June 3 2026, $435 Call → strike×1000 = 435000 → 00435000.
    String md = YahooOptionLink.markdownFromParts("TSLA", "260603", 'C', "435");
    assertThat(md)
        .isEqualTo(
            "[TSLA 260603C00435000]" + "(https://finance.yahoo.com/quote/TSLA260603C00435000/)");
  }

  @Test
  void fromPartsAcceptsIsoDateAndDecimalStrike() {
    // ISO expiry + a fractional strike (137.5 → 137500 → 00137500).
    String md =
        YahooOptionLink.markdownFromParts("NVDA", "2026-05-16", 'c', new BigDecimal("137.5"));
    assertThat(md).contains("quote/NVDA260516C00137500/");
    assertThat(md).contains("[NVDA 260516C00137500]");
  }

  @Test
  void fromPartsLowercaseRightIsUppercased() {
    String md = YahooOptionLink.markdownFromParts("SPY", "260116", 'p', "500");
    assertThat(md).contains("260116P00500000");
  }

  @Test
  void malformedOccDegradesToPlainText() {
    // Not a valid OCC (too short / no strike block) → plain trimmed text, no markdown link.
    String md = YahooOptionLink.markdown("NOTANOCC");
    assertThat(md).isEqualTo("NOTANOCC");
    assertThat(md).doesNotContain("finance.yahoo.com");
    assertThat(md).doesNotContain("[");
  }

  @Test
  void nullAndBlankOccBecomeNa() {
    assertThat(YahooOptionLink.markdown(null)).isEqualTo("n/a");
    assertThat(YahooOptionLink.markdown("   ")).isEqualTo("n/a");
  }

  @Test
  void fromPartsMissingPartsDegradeToReadablePlainText() {
    // Missing strike → no OCC; fall back to a readable plain rendering, never a link, never throws.
    String md = YahooOptionLink.markdownFromParts("AAPL", "260116", 'C', null);
    assertThat(md).doesNotContain("finance.yahoo.com");
    assertThat(md).contains("AAPL");

    // Unknown right → fall back.
    String md2 = YahooOptionLink.markdownFromParts("AAPL", "260116", 'X', "200");
    assertThat(md2).doesNotContain("finance.yahoo.com");
  }

  @Test
  void neverThrowsOnGarbage() {
    assertThatCode(
            () -> {
              YahooOptionLink.markdown("");
              YahooOptionLink.markdown("###");
              YahooOptionLink.markdownFromParts(null, null, ' ', null);
              YahooOptionLink.markdownFromParts("AAPL", "bad-date", 'C', "abc");
            })
        .doesNotThrowAnyException();
  }
}
