package com.ohmytradeagent.tdbff.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.tdbff.proximity.MarketDataQuoteClient;
import com.ohmytradeagent.tdbff.proximity.MarketDataQuoteClient.EquityBar;
import com.ohmytradeagent.tdbff.watchlist.WatchlistDailyReader.Idea;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class WatchlistTodayTest {

  private static final LocalDate DAY = LocalDate.of(2026, 10, 15);
  // 10:58 ET (EDT, UTC-4).
  private static final Instant MIDDAY = Instant.parse("2026-10-15T14:58:00Z");

  private static final Idea NVDA_CALL =
      new Idea("NVDA", "C", new BigDecimal("185"), "ABOVE", new BigDecimal("181.00"));
  private static final Idea NVDA_PUT =
      new Idea("NVDA", "P", new BigDecimal("175"), "BELOW", new BigDecimal("178.00"));

  private static EquityBar bar(String t, String h, String l, String c) {
    return new EquityBar(Instant.parse(t), new BigDecimal(h), new BigDecimal(l), new BigDecimal(c));
  }

  private static final List<EquityBar> SESSION =
      List.of(
          bar("2026-10-15T13:30:00Z", "180.10", "179.60", "180.00"),
          bar("2026-10-15T14:42:00Z", "181.20", "180.70", "181.06"),
          bar("2026-10-15T14:57:00Z", "182.60", "182.10", "182.40"));

  @Test
  void firstCross_isTheFirstBarWhoseRangeReachesTheLevel() {
    assertThat(WatchlistCrossing.firstCross(SESSION, "ABOVE", new BigDecimal("181.00")))
        .isEqualTo(Instant.parse("2026-10-15T14:42:00Z"));
    assertThat(WatchlistCrossing.firstCross(SESSION, "BELOW", new BigDecimal("179.60")))
        .isEqualTo(Instant.parse("2026-10-15T13:30:00Z"));
    assertThat(WatchlistCrossing.firstCross(SESSION, "BELOW", new BigDecimal("178.00"))).isNull();
  }

  @Test
  void firstCross_touchingTheLevelExactlyCounts() {
    assertThat(WatchlistCrossing.firstCross(SESSION, "ABOVE", new BigDecimal("181.20")))
        .isEqualTo(Instant.parse("2026-10-15T14:42:00Z"));
    assertThat(WatchlistCrossing.firstCross(SESSION, "BELOW", new BigDecimal("180.70")))
        .isEqualTo(Instant.parse("2026-10-15T13:30:00Z"));
  }

  @Test
  void firstCross_countsAGapStraightPastTheLevel() {
    // Opened above the level: the strategy's band would skip this; the display shows it crossed.
    assertThat(WatchlistCrossing.firstCross(SESSION, "ABOVE", new BigDecimal("175.00")))
        .isEqualTo(Instant.parse("2026-10-15T13:30:00Z"));
  }

  @Test
  void today_marksEachIdea_andReadsEachTickerOnce() {
    WatchlistDailyReader reader = mock(WatchlistDailyReader.class);
    MarketDataQuoteClient md = mock(MarketDataQuoteClient.class);
    when(reader.ideasFor(DAY)).thenReturn(List.of(NVDA_CALL, NVDA_PUT));
    when(md.equityBars1m(eq("NVDA"), any(), any())).thenReturn(SESSION);

    WatchlistToday.Today today = service(reader, md, MIDDAY).today();

    assertThat(today.ideas()).hasSize(2);
    WatchlistToday.IdeaView call = today.ideas().get(0);
    assertThat(call.status()).isEqualTo("crossed");
    assertThat(call.crossedAt()).isEqualTo(Instant.parse("2026-10-15T14:42:00Z"));
    assertThat(call.last()).isEqualByComparingTo("182.40");
    assertThat(call.distancePct()).isEqualByComparingTo("0.77");
    assertThat(today.ideas().get(1).status()).isEqualTo("waiting");
    verify(md, times(1))
        .equityBars1m(
            "NVDA", Instant.parse("2026-10-15T13:30:00Z"), Instant.parse("2026-10-15T14:58:00Z"));
  }

  @Test
  void today_barsUnreadable_isUnknown_neverWaiting() {
    WatchlistDailyReader reader = mock(WatchlistDailyReader.class);
    MarketDataQuoteClient md = mock(MarketDataQuoteClient.class);
    when(reader.ideasFor(DAY)).thenReturn(List.of(NVDA_CALL));
    when(md.equityBars1m(eq("NVDA"), any(), any())).thenReturn(null);

    WatchlistToday.IdeaView v = service(reader, md, MIDDAY).today().ideas().get(0);

    assertThat(v.status()).isEqualTo("unknown");
    assertThat(v.crossedAt()).isNull();
  }

  @Test
  void today_beforeTheOpen_isWaiting_withoutReadingBars() {
    WatchlistDailyReader reader = mock(WatchlistDailyReader.class);
    MarketDataQuoteClient md = mock(MarketDataQuoteClient.class);
    when(reader.ideasFor(DAY)).thenReturn(List.of(NVDA_CALL));

    WatchlistToday.IdeaView v =
        service(reader, md, Instant.parse("2026-10-15T12:20:00Z")).today().ideas().get(0);

    assertThat(v.status()).isEqualTo("waiting");
    verify(md, never()).equityBars1m(any(), any(), any());
  }

  @Test
  void today_afterTheClose_readsOnlyTheRegularSession() {
    WatchlistDailyReader reader = mock(WatchlistDailyReader.class);
    MarketDataQuoteClient md = mock(MarketDataQuoteClient.class);
    when(reader.ideasFor(DAY)).thenReturn(List.of(NVDA_CALL));
    when(md.equityBars1m(eq("NVDA"), any(), any())).thenReturn(SESSION);

    service(reader, md, Instant.parse("2026-10-15T22:00:00Z")).today();

    verify(md)
        .equityBars1m(
            "NVDA", Instant.parse("2026-10-15T13:30:00Z"), Instant.parse("2026-10-15T20:00:00Z"));
  }

  @Test
  void today_nothingPosted_isNullIdeas() {
    WatchlistDailyReader reader = mock(WatchlistDailyReader.class);
    when(reader.ideasFor(DAY)).thenReturn(null);

    WatchlistToday.Today today = service(reader, mock(MarketDataQuoteClient.class), MIDDAY).today();

    assertThat(today.etDate()).isEqualTo(DAY);
    assertThat(today.ideas()).isNull();
  }

  @Test
  void today_reusesBarsWithinTheCacheWindow() {
    WatchlistDailyReader reader = mock(WatchlistDailyReader.class);
    MarketDataQuoteClient md = mock(MarketDataQuoteClient.class);
    when(reader.ideasFor(DAY)).thenReturn(List.of(NVDA_CALL));
    when(md.equityBars1m(eq("NVDA"), any(), any())).thenReturn(SESSION);
    WatchlistToday svc = service(reader, md, MIDDAY);

    svc.today();
    svc.today();

    verify(md, times(1)).equityBars1m(eq("NVDA"), any(), any());
  }

  private static WatchlistToday service(
      WatchlistDailyReader reader, MarketDataQuoteClient md, Instant now) {
    return new WatchlistToday(reader, md, Clock.fixed(now, ZoneOffset.UTC));
  }
}
