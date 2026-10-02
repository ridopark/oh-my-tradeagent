package com.ohmytradeagent.marketdata.marks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.marketdata.health.FeedHealth;
import com.ohmytradeagent.marketdata.provider.MarketDataProvider;
import com.ohmytradeagent.marketdata.provider.PremiumFeedStatus;
import com.ohmytradeagent.marketdata.provider.Quote;
import com.ohmytradeagent.marketdata.provider.Tick;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DisplayMarksServiceTest {

  private static final String OCC = "NVDA260516C00140000";
  private static final String OCC2 = "NVDA260516P00130000";
  private static final Instant T0 = Instant.parse("2026-10-01T14:30:00Z");

  private MarketDataProvider provider;
  private FeedHealth feedHealth;
  private MutableClock clock;
  private ManualScheduler scheduler;
  private DisplayMarksService service;

  @BeforeEach
  void setUp() {
    provider = mock(MarketDataProvider.class);
    when(provider.snapshotQuote(anyString()))
        .thenAnswer(inv -> Optional.of(quote(inv.getArgument(0), "2.90", "3.00")));
    when(provider.snapshotEquityTrade(anyString()))
        .thenAnswer(
            inv ->
                Optional.of(
                    new Tick(
                        inv.getArgument(0),
                        new BigDecimal("140.10"),
                        OffsetDateTime.parse("2026-10-01T14:29:59.500Z"))));
    feedHealth = new FeedHealth(new SimpleMeterRegistry());
    clock = new MutableClock(T0);
    scheduler = new ManualScheduler();
    service = new DisplayMarksService(provider, feedHealth, clock, 500L, scheduler.executor);
  }

  private static Quote quote(String occ, String bid, String ask) {
    BigDecimal b = new BigDecimal(bid);
    BigDecimal a = new BigDecimal(ask);
    return new Quote(
        occ,
        b,
        b.add(a).divide(BigDecimal.valueOf(2)),
        a,
        OffsetDateTime.parse("2026-10-01T14:29:59Z"));
  }

  private DisplayMarksService.DisplayMark only(List<DisplayMarksService.DisplayMark> marks) {
    assertThat(marks).hasSize(1);
    return marks.get(0);
  }

  @Test
  void firstSight_isWarming_thenCarriesTheFirstPolledQuote() {
    var first = only(service.marks(List.of(OCC)));
    assertThat(first.occ()).isEqualTo(OCC);
    assertThat(first.warming()).isTrue();
    assertThat(first.capped()).isFalse();
    assertThat(first.quote()).isNull();
    assertThat(scheduler.tasks).hasSize(1);
    assertThat(scheduler.periodsMs).containsExactly(500L);

    scheduler.runAll();

    var second = only(service.marks(List.of(OCC)));
    assertThat(second.warming()).isFalse();
    assertThat(second.quote().bid()).isEqualByComparingTo("2.90");
    assertThat(second.quote().ask()).isEqualByComparingTo("3.00");
    assertThat(second.underlying().ticker()).isEqualTo("NVDA");
    assertThat(second.underlying().price()).isEqualByComparingTo("140.10");
    // A repeat request for a known OCC never schedules a second task.
    assertThat(scheduler.tasks).hasSize(1);
  }

  @Test
  void ttl_thirtySecondsWithoutARequest_cancelsTheTaskAndStopsPolling() {
    service.marks(List.of(OCC));
    scheduler.runAll();
    clock.advance(Duration.ofSeconds(29));
    scheduler.runAll();
    verify(provider, times(2)).snapshotQuote(OCC);
    verify(scheduler.futures.get(0), never()).cancel(org.mockito.ArgumentMatchers.anyBoolean());

    clock.advance(Duration.ofSeconds(2)); // 31s since the only request
    scheduler.runAll();
    verify(scheduler.futures.get(0)).cancel(false);

    // Even if the (cancelled) task fired again, it must not poll.
    scheduler.runAll();
    scheduler.runAll();
    verify(provider, times(2)).snapshotQuote(OCC);

    // A fresh request after expiry re-registers with a new task and warms up again.
    var again = only(service.marks(List.of(OCC)));
    assertThat(again.warming()).isTrue();
    assertThat(scheduler.tasks).hasSize(2);
  }

  @Test
  void ttl_aRepeatRequestKeepsTheInterestAlive() {
    service.marks(List.of(OCC));
    clock.advance(Duration.ofSeconds(20));
    service.marks(List.of(OCC));
    clock.advance(Duration.ofSeconds(20)); // 40s since first, 20s since last request
    scheduler.runAll();
    verify(scheduler.futures.get(0), never()).cancel(org.mockito.ArgumentMatchers.anyBoolean());
    verify(provider, times(1)).snapshotQuote(OCC);
  }

  @Test
  void cap_twentySixOccs_admitsTwentyFiveAndCapsTheRest() {
    List<String> occs = new ArrayList<>();
    for (int i = 0; i < 26; i++) {
      occs.add("NVDA260516C00%03d000".formatted(100 + i));
    }

    var marks = service.marks(occs);

    assertThat(marks).hasSize(26);
    assertThat(marks.subList(0, 25)).allSatisfy(m -> assertThat(m.capped()).isFalse());
    var capped = marks.get(25);
    assertThat(capped.occ()).isEqualTo(occs.get(25));
    assertThat(capped.capped()).isTrue();
    assertThat(capped.warming()).isFalse();
    assertThat(capped.quote()).isNull();
    assertThat(capped.underlying().price()).isNull();
    assertThat(scheduler.tasks).hasSize(25);

    scheduler.runAll();
    verify(provider, never()).snapshotQuote(occs.get(25));
    var cappedAgain = service.marks(List.of(occs.get(25))).get(0);
    assertThat(cappedAgain.capped()).isTrue();
    assertThat(cappedAgain.quote()).isNull();
  }

  @Test
  void reuse_trailPollActive_copiesTheCachedQuoteAndIssuesNoSnapshot() {
    Quote polled = quote("NVDA  260516C00140000", "4.10", "4.20");
    when(provider.premiumPollActive(OCC)).thenReturn(true);
    when(provider.lastPolledQuote(OCC)).thenReturn(Optional.of(polled));

    service.marks(List.of(OCC));
    for (int i = 0; i < 10; i++) {
      scheduler.runAll();
    }

    verify(provider, never()).snapshotQuote(anyString());
    var m = only(service.marks(List.of(OCC)));
    assertThat(m.warming()).isFalse();
    assertThat(m.quote().bid()).isEqualByComparingTo("4.10");
  }

  @Test
  void reuse_trailPollActiveButNoQuoteYet_staysWarmingWithoutASnapshot() {
    when(provider.premiumPollActive(OCC)).thenReturn(true);
    when(provider.lastPolledQuote(OCC)).thenReturn(Optional.empty());

    service.marks(List.of(OCC));
    scheduler.runAll();
    scheduler.runAll();

    verify(provider, never()).snapshotQuote(anyString());
    assertThat(only(service.marks(List.of(OCC))).warming()).isTrue();
  }

  @Test
  void underlying_snapshotIsFetchedAtMostOncePerSecondPerTicker() {
    service.marks(List.of(OCC, OCC2)); // both NVDA
    scheduler.runAll();
    scheduler.runAll();
    clock.advance(Duration.ofMillis(999));
    scheduler.runAll();
    verify(provider, times(1)).snapshotEquityTrade("NVDA");

    clock.advance(Duration.ofMillis(1));
    scheduler.runAll();
    scheduler.runAll();
    verify(provider, times(2)).snapshotEquityTrade("NVDA");
  }

  @Test
  void underlying_freshSipTickWinsWhenTheEquityFeedIsConnected() {
    feedHealth.markConnected(FeedHealth.Feed.EQUITY);
    OffsetDateTime tickAt = OffsetDateTime.ofInstant(T0.minusMillis(1500), ZoneOffset.UTC);
    when(provider.lastEquityTick("NVDA"))
        .thenReturn(Optional.of(new Tick("NVDA", new BigDecimal("141.55"), tickAt)));

    service.marks(List.of(OCC));
    scheduler.runAll();

    verify(provider, never()).snapshotEquityTrade(anyString());
    var u = only(service.marks(List.of(OCC))).underlying();
    assertThat(u.price()).isEqualByComparingTo("141.55");
    assertThat(u.at()).isEqualTo(tickAt.toInstant());
  }

  @Test
  void underlying_staleSipTickOrDisconnectedFeed_fallsBackToTheSnapshot() {
    feedHealth.markConnected(FeedHealth.Feed.EQUITY);
    OffsetDateTime old = OffsetDateTime.ofInstant(T0.minusSeconds(3), ZoneOffset.UTC);
    when(provider.lastEquityTick("NVDA"))
        .thenReturn(Optional.of(new Tick("NVDA", new BigDecimal("139.00"), old)));

    service.marks(List.of(OCC));
    scheduler.runAll();

    verify(provider, times(1)).snapshotEquityTrade("NVDA");
    var u = only(service.marks(List.of(OCC))).underlying();
    assertThat(u.price()).isEqualByComparingTo("140.10");
    assertThat(u.at()).isEqualTo(Instant.parse("2026-10-01T14:29:59.500Z"));

    // Fresh tick, but the feed is down -> still the snapshot.
    feedHealth.markDisconnected(FeedHealth.Feed.EQUITY);
    when(provider.lastEquityTick("NVDA"))
        .thenReturn(
            Optional.of(
                new Tick(
                    "NVDA",
                    new BigDecimal("150.00"),
                    OffsetDateTime.ofInstant(T0, ZoneOffset.UTC))));
    assertThat(only(service.marks(List.of(OCC))).underlying().price())
        .isEqualByComparingTo("140.10");
  }

  @Test
  void underlying_adjustedRoot_hasNullPriceAndNoLookup() {
    var m = only(service.marks(List.of("TSLA1260516C00200000")));
    scheduler.runAll();

    verify(provider, never()).snapshotEquityTrade(anyString());
    assertThat(m.underlying().ticker()).isEqualTo("TSLA1");
    assertThat(m.underlying().price()).isNull();
  }

  @Test
  void tickerOf_parsesTheRootFromCompactOrPaddedOcc() {
    assertThat(DisplayMarksService.tickerOf("NVDA260516C00140000")).isEqualTo("NVDA");
    assertThat(DisplayMarksService.tickerOf("SPY   260609P00731000")).isEqualTo("SPY");
    assertThat(DisplayMarksService.tickerOf("SHORT")).isNull();
  }

  @Test
  void aThrowingProvider_neverKillsTheTask() {
    when(provider.snapshotQuote(anyString())).thenThrow(new RuntimeException("boom"));
    service.marks(List.of(OCC));
    scheduler.runAll(); // must not throw
    assertThat(only(service.marks(List.of(OCC))).warming()).isTrue();
  }

  @Test
  void underlying_snapshotAt_isTheTradesOwnTimestampNotFetchTime() {
    OffsetDateTime hourOld = OffsetDateTime.ofInstant(T0.minusSeconds(3600), ZoneOffset.UTC);
    when(provider.snapshotEquityTrade("NVDA"))
        .thenReturn(Optional.of(new Tick("NVDA", new BigDecimal("139.00"), hourOld)));

    service.marks(List.of(OCC));
    scheduler.runAll();

    var u = only(service.marks(List.of(OCC))).underlying();
    assertThat(u.price()).isEqualByComparingTo("139.00");
    assertThat(u.at()).isEqualTo(T0.minusSeconds(3600));
  }

  @Test
  void budget_twentyFiveOccsTickingInsideOneSecond_issueAtMostTenRestCalls() {
    List<String> occs = new ArrayList<>();
    for (int i = 0; i < 25; i++) {
      occs.add("NVDA260516C00%03d000".formatted(100 + i));
    }
    service.marks(occs);
    for (int tick = 0; tick < 4; tick++) { // 4 ticks x 25 OCCs, all within 999ms
      scheduler.runAll();
      clock.advance(Duration.ofMillis(250));
    }
    verify(provider, org.mockito.Mockito.atMost(10)).snapshotQuote(anyString());
    // Shared budget: option and stock snapshots together stay within 10.
    assertThat(
            org.mockito.Mockito.mockingDetails(provider).getInvocations().stream()
                .filter(
                    inv ->
                        inv.getMethod().getName().equals("snapshotQuote")
                            || inv.getMethod().getName().equals("snapshotEquityTrade"))
                .count())
        .isEqualTo(10);

    // Next second: the budget refills.
    clock.advance(Duration.ofSeconds(1));
    scheduler.runAll();
    verify(provider, org.mockito.Mockito.atLeast(11)).snapshotQuote(anyString());
  }

  @Test
  void budget_noPermit_skipsTheRestCallAndKeepsThePreviousCachedQuote() {
    service.marks(List.of(OCC));
    scheduler.tasks.get(0).run(); // OCC cached
    clock.advance(Duration.ofSeconds(1)); // fresh budget
    List<String> occs = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      occs.add("NVDA260516C00%03d000".formatted(100 + i));
    }
    service.marks(occs);
    for (int i = 1; i <= 10; i++) {
      scheduler.tasks.get(i).run(); // 9 option + 1 stock snapshot: budget spent
    }

    scheduler.tasks.get(0).run(); // no permit left this second

    verify(provider, times(1)).snapshotQuote(OCC);
    var m = service.marks(List.of(OCC)).get(0);
    assertThat(m.warming()).isFalse();
    assertThat(m.quote().bid()).isEqualByComparingTo("2.90");
  }

  @Test
  void budget_trailReuseConsumesNoPermit() {
    when(provider.premiumPollActive(anyString())).thenReturn(true);
    when(provider.lastPolledQuote(anyString())).thenReturn(Optional.of(quote(OCC, "4.10", "4.20")));
    when(provider.snapshotEquityTrade(anyString())).thenReturn(Optional.empty());
    List<String> occs = new ArrayList<>();
    for (int i = 0; i < 24; i++) {
      occs.add("NVDA260516C00%03d000".formatted(100 + i));
    }
    service.marks(occs);
    scheduler.runAll();
    scheduler.runAll();
    verify(provider, never()).snapshotQuote(anyString());
    // Only the stock snapshot drew a permit; an uncached OCC can still poll this second.
    when(provider.premiumPollActive(anyString())).thenReturn(false);
    service.marks(List.of("AMD260516C00150000"));
    scheduler.tasks.get(24).run();
    verify(provider).snapshotQuote("AMD260516C00150000");
  }

  @Test
  void schedulingFailure_rollsBackTheRegistrySlot() {
    when(scheduler.executor.scheduleAtFixedRate(
            org.mockito.ArgumentMatchers.any(Runnable.class),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.any()))
        .thenThrow(new java.util.concurrent.RejectedExecutionException("shutting down"));
    List<String> occs = new ArrayList<>();
    for (int i = 0; i < 26; i++) {
      occs.add("NVDA260516C00%03d000".formatted(100 + i));
    }
    var marks = service.marks(occs); // must not throw
    // Nothing was admitted, so the 26th is not capped by 25 leaked slots.
    assertThat(marks).allSatisfy(m -> assertThat(m.capped()).isFalse());
  }

  @Test
  void anErrorInAPoll_releasesTheSlotSoTheOccCanReRegister() {
    when(provider.snapshotQuote(anyString())).thenThrow(new LinkageError("bad class"));
    service.marks(List.of(OCC));
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> scheduler.runAll())
        .isInstanceOf(LinkageError.class);
    verify(scheduler.futures.get(0)).cancel(false);
    // Slot freed: the next request re-registers with a fresh task.
    assertThat(only(service.marks(List.of(OCC))).warming()).isTrue();
    assertThat(scheduler.tasks).hasSize(2);
  }

  // --- polled_at (PLAN-2026-10-01-live-marks-polled-at P1) ---

  @Test
  void quietQuote_advancesPolledAt_notQuoteAt() {
    service.marks(List.of(OCC));
    scheduler.runAll();
    var first = only(service.marks(List.of(OCC)));
    assertThat(first.polledAt()).isEqualTo(T0);

    clock.advance(Duration.ofMillis(500));
    scheduler.runAll(); // same latestQuote.t: the market did not move

    var second = only(service.marks(List.of(OCC)));
    assertThat(second.quote().retrievedAt()).isEqualTo(first.quote().retrievedAt());
    assertThat(second.polledAt()).isEqualTo(T0.plusMillis(500));
  }

  @Test
  void emptySnapshot_leavesPolledAtUnchanged() {
    service.marks(List.of(OCC));
    scheduler.runAll();
    when(provider.snapshotQuote(anyString())).thenReturn(Optional.empty());
    clock.advance(Duration.ofSeconds(5));
    scheduler.runAll();

    var m = only(service.marks(List.of(OCC)));
    assertThat(m.quote().bid()).isEqualByComparingTo("2.90");
    assertThat(m.polledAt()).isEqualTo(T0);
  }

  @Test
  void throwingSnapshot_leavesPolledAtUnchanged() {
    service.marks(List.of(OCC));
    scheduler.runAll();
    when(provider.snapshotQuote(anyString())).thenThrow(new RuntimeException("boom"));
    clock.advance(Duration.ofSeconds(5));
    scheduler.runAll();

    var m = only(service.marks(List.of(OCC)));
    assertThat(m.quote().bid()).isEqualByComparingTo("2.90");
    assertThat(m.polledAt()).isEqualTo(T0);
  }

  @Test
  void noPermit_leavesPolledAtUnchanged() {
    service.marks(List.of(OCC));
    scheduler.tasks.get(0).run(); // OCC cached at T0
    clock.advance(Duration.ofSeconds(1)); // fresh budget
    List<String> occs = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      occs.add("NVDA260516C00%03d000".formatted(100 + i));
    }
    service.marks(occs);
    for (int i = 1; i <= 10; i++) {
      scheduler.tasks.get(i).run(); // 9 option + 1 stock snapshot: budget spent
    }

    scheduler.tasks.get(0).run(); // no permit left this second

    verify(provider, times(1)).snapshotQuote(OCC);
    assertThat(service.marks(List.of(OCC)).get(0).polledAt()).isEqualTo(T0);
  }

  @Test
  void trailReuse_reportsTrailLastPollOkAt() {
    String padded = "NVDA  260516C00140000";
    Instant trailOkAt = T0.minusMillis(300);
    when(provider.premiumPollActive(OCC)).thenReturn(true);
    when(provider.lastPolledQuote(OCC)).thenReturn(Optional.of(quote(padded, "4.10", "4.20")));
    when(provider.premiumFeedStatus())
        .thenReturn(Map.of(padded, new PremiumFeedStatus(padded, 1, 42L, trailOkAt, null, 0)));

    service.marks(List.of(OCC)); // compact OCC requested
    scheduler.runAll();

    var m = only(service.marks(List.of(OCC)));
    assertThat(m.quote().bid()).isEqualByComparingTo("4.10");
    assertThat(m.polledAt()).isEqualTo(trailOkAt);
    verify(provider, never()).snapshotQuote(anyString());
  }

  @Test
  void trailReuse_noStatusRow_polledAtNull_quoteStillCached() {
    when(provider.premiumPollActive(OCC)).thenReturn(true);
    when(provider.lastPolledQuote(OCC)).thenReturn(Optional.of(quote(OCC, "4.10", "4.20")));
    when(provider.premiumFeedStatus()).thenReturn(Map.of());

    service.marks(List.of(OCC));
    scheduler.runAll();

    var m = only(service.marks(List.of(OCC)));
    assertThat(m.warming()).isFalse();
    assertThat(m.quote().bid()).isEqualByComparingTo("4.10");
    assertThat(m.polledAt()).isNull();
  }
}
