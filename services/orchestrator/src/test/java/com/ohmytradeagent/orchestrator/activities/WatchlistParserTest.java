package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.orchestrator.activities.WatchlistParser.ParseResult;
import com.ohmytradeagent.orchestrator.activities.WatchlistParser.TickerWatch;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The watchlist parser turns the free-text daily watchlist into structured per-ticker rows
 * (call/put legs). Continuation lines (no ticker) inherit the ticker above them; the author/time
 * header and blank lines are ignored. Any unrecognized prose line marks the result not-clean so the
 * caller falls back to raw text.
 */
class WatchlistParserTest {

  private static final String SAMPLE =
      """
      TradingTheTrend — 8:19 AM

      SPY   762c  >  761.00
      753p  <  754.00
      SHOP  121c  >  120.00
      ORCL  205c  >  204.50
      UNH   512c  >  511.00
      TSM   178c  >  177.00
      TSLA  430c  >  425.00
      410p  <  413.00
      """;

  @Test
  void parsesFullSampleWithGroupingAndContinuationInheritance() {
    ParseResult result = WatchlistParser.parse(SAMPLE);

    assertThat(result.clean()).isTrue();
    assertThat(result.rows())
        .extracting(TickerWatch::ticker)
        .containsExactly("SPY", "SHOP", "ORCL", "UNH", "TSM", "TSLA");

    TickerWatch spy = result.rows().get(0);
    assertThat(spy.call()).isNotNull();
    assertThat(spy.call().strike()).isEqualTo("762");
    assertThat(spy.call().right()).isEqualTo('c');
    assertThat(spy.call().trigger()).isEqualByComparingTo(new BigDecimal("761.00"));
    // 753p < 754.00 is a continuation line that inherits SPY.
    assertThat(spy.put()).isNotNull();
    assertThat(spy.put().strike()).isEqualTo("753");
    assertThat(spy.put().right()).isEqualTo('p');
    assertThat(spy.put().trigger()).isEqualByComparingTo(new BigDecimal("754.00"));

    TickerWatch shop = result.rows().get(1);
    assertThat(shop.call()).isNotNull();
    assertThat(shop.call().strike()).isEqualTo("121");
    assertThat(shop.put()).isNull();

    TickerWatch tsla = result.rows().get(5);
    assertThat(tsla.call().strike()).isEqualTo("430");
    assertThat(tsla.call().trigger()).isEqualByComparingTo(new BigDecimal("425.00"));
    assertThat(tsla.put()).isNotNull();
    assertThat(tsla.put().strike()).isEqualTo("410");
    assertThat(tsla.put().trigger()).isEqualByComparingTo(new BigDecimal("413.00"));
  }

  @Test
  void rightIsCaseInsensitive() {
    ParseResult result = WatchlistParser.parse("SPY 762C > 761.00\n753P < 754.00");

    assertThat(result.clean()).isTrue();
    TickerWatch spy = result.rows().get(0);
    assertThat(spy.call().right()).isEqualTo('c');
    assertThat(spy.put().right()).isEqualTo('p');
  }

  @Test
  void decimalStrikeIsPreservedAndIgnoresBlankLines() {
    ParseResult result = WatchlistParser.parse("\n\nSPY 762.5c > 761.00\n\n");

    assertThat(result.clean()).isTrue();
    assertThat(result.rows()).hasSize(1);
    assertThat(result.rows().get(0).call().strike()).isEqualTo("762.5");
  }

  @Test
  void authorHeaderLineDoesNotMakeResultDirty() {
    // The header line contains no >/< and matches the author seam ("—", " AM").
    ParseResult result = WatchlistParser.parse("TradingTheTrend — 8:19 AM\nSPY 762c > 761.00");

    assertThat(result.clean()).isTrue();
    assertThat(result.rows()).hasSize(1);
  }

  @Test
  void proseLineMarksResultNotClean() {
    ParseResult result = WatchlistParser.parse("lol no setups today");

    assertThat(result.clean()).isFalse();
    assertThat(result.rows()).isEmpty();
  }

  @Test
  void markerlessCommentaryAmongValidPlaysIsTolerated() {
    // Marker-less prose (no comparator, no strike+right token) is ignorable chatter now, so a
    // valid play alongside a friendly comment stays clean (renders the embed, not raw fallback).
    ParseResult result =
        WatchlistParser.parse("SPY 762c > 761.00\nthis is random commentary about the market");

    assertThat(result.clean()).isTrue();
    assertThat(result.rows()).extracting(TickerWatch::ticker).containsExactly("SPY");
  }

  @Test
  void malformedLevelAmongValidPlaysMarksResultNotClean() {
    // A line that DOES carry a comparator but is a broken level still flips clean=false.
    ParseResult result = WatchlistParser.parse("SPY 762c > 761.00\nfoo > bar baz");

    assertThat(result.clean()).isFalse();
  }

  @Test
  void nullOrBlankIsNotCleanWithNoRows() {
    // clean now requires ≥1 parsed row, so an empty watchlist is not clean (→ raw fallback). The
    // caller already double-guards on non-empty rows, so behavior is unchanged for empty input.
    assertThat(WatchlistParser.parse(null).clean()).isFalse();
    assertThat(WatchlistParser.parse(null).rows()).isEmpty();
    assertThat(WatchlistParser.parse("   \n\n").rows()).isEmpty();
    assertThat(WatchlistParser.parse("   \n\n").clean()).isFalse();
  }

  @Test
  void parse_watchlistWithTrailingGreeting_isCleanAndIgnoresChatter() {
    // Real sample + a trailing friendly sign-off and blank lines: the greeting carries no
    // comparator and no strike+right token, so it is ignorable chatter — it must not flip clean.
    ParseResult result = WatchlistParser.parse(SAMPLE + "\n\nGood luck @everyone\n\n");

    assertThat(result.clean()).isTrue();
    assertThat(result.rows())
        .extracting(TickerWatch::ticker)
        .containsExactly("SPY", "SHOP", "ORCL", "UNH", "TSM", "TSLA");
  }

  @Test
  void parse_malformedLevelLineWithComparator_staysUncleanForRawFallback() {
    // Has a comparator but does not fully parse — must still flip clean=false so a real level is
    // never silently dropped (raw fallback preserved).
    ParseResult result = WatchlistParser.parse("SPY 762c > 761.00\nSPY > 761");

    assertThat(result.clean()).isFalse();
  }

  @Test
  void parse_strikeRightTokenWithoutComparator_staysUncleanForRawFallback() {
    // A line with a <number><c|p> strike+right token but NO comparator still does not fully parse,
    // yet it is level-ish (a dropped direction) — must stay unclean so the raw fallback fires.
    ParseResult result = WatchlistParser.parse("SPY 762c > 761.00\nSPY 745p 748.00");

    assertThat(result.clean()).isFalse();
  }

  @Test
  void parse_chatterOnlyWithNoRows_isNotClean() {
    // Pure chatter with no parsed rows: clean requires at least one row.
    ParseResult result = WatchlistParser.parse("Good luck @everyone\nhello there");

    assertThat(result.clean()).isFalse();
    assertThat(result.rows()).isEmpty();
  }
}
