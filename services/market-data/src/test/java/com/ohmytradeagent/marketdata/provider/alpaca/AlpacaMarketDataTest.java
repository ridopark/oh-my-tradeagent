package com.ohmytradeagent.marketdata.provider.alpaca;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.marketdata.provider.PremiumFeedStatus;
import com.ohmytradeagent.marketdata.provider.Quote;
import com.ohmytradeagent.marketdata.provider.Subscription;
import com.ohmytradeagent.marketdata.provider.Tick;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Drives {@link AlpacaMarketData} against an in-process {@link MockWebServer} for the REST snapshot
 * path. The option-premium feed is a REST poll (not a WS): tests exercise {@link
 * AlpacaMarketData#pollOnce} directly against an enqueued snapshot for the fan-out, and the stock
 * trade stream's message handler via {@link AlpacaMarketData#dispatchStockWsMessage}.
 */
class AlpacaMarketDataTest {

  private MockWebServer server;
  private AlpacaMarketData provider;
  private final ObjectMapper mapper = new ObjectMapper();
  private ScheduledExecutorService scheduler;

  @BeforeEach
  void start() throws IOException {
    server = new MockWebServer();
    server.start();
    RestClient client =
        RestClient.builder()
            .baseUrl(server.url("/").toString().replaceAll("/$", ""))
            .defaultHeader("APCA-API-KEY-ID", "key-id-for-test")
            .defaultHeader("APCA-API-SECRET-KEY", "key-secret-for-test")
            .defaultHeader("Accept", "application/json")
            .build();
    AlpacaMarketDataProperties props =
        new AlpacaMarketDataProperties(
            server.url("/").toString().replaceAll("/$", ""),
            "wss://example.invalid/should-not-connect",
            "key-id-for-test",
            "key-secret-for-test",
            "",
            "",
            null);
    scheduler = Executors.newSingleThreadScheduledExecutor();
    provider = new AlpacaMarketData(client, mapper, props, HttpClient.newHttpClient(), scheduler);
  }

  @AfterEach
  void stop() throws IOException {
    scheduler.shutdownNow();
    server.shutdown();
  }

  @Test
  void snapshotQuote_happyPath_extractsBidAskAndMid() throws Exception {
    // Alpaca keys the snapshot response by the COMPACT OCC it received (no space-padding).
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"snapshots\":{\"NVDA260516C00140000\":{\"latestQuote\":"
                    + "{\"bp\":1.20,\"ap\":1.30,\"t\":\"2026-05-15T17:22:31.123Z\"}}}}"));

    // Caller passes the space-padded canonical OCC; the provider must compact it for Alpaca.
    Optional<Quote> q = provider.snapshotQuote("NVDA  260516C00140000");

    assertThat(q).isPresent();
    Quote quote = q.get();
    assertThat(quote.bid()).isEqualByComparingTo("1.20");
    assertThat(quote.ask()).isEqualByComparingTo("1.30");
    assertThat(quote.mid()).isEqualByComparingTo("1.25");

    RecordedRequest req = server.takeRequest();
    assertThat(req.getMethod()).isEqualTo("GET");
    assertThat(req.getPath()).startsWith("/v1beta1/options/snapshots");
    // Regression guard: the request must carry the COMPACT symbol — a padded OCC 400s
    // ("invalid symbol") at Alpaca and silently degrades bounded limits to marketable.
    assertThat(req.getPath()).contains("NVDA260516C00140000");
    assertThat(req.getPath()).doesNotContain("%20").doesNotContain("+");
    assertThat(req.getHeader("APCA-API-KEY-ID")).isEqualTo("key-id-for-test");
  }

  @Test
  void snapshotQuote_missingLatestQuote_returnsEmpty() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"snapshots\":{}}"));

    Optional<Quote> q = provider.snapshotQuote("NVDA  260516C00140000");
    assertThat(q).isEmpty();
  }

  @Test
  void snapshotQuote_5xx_returnsEmpty() {
    server.enqueue(new MockResponse().setResponseCode(503).setBody("{\"message\":\"down\"}"));
    Optional<Quote> q = provider.snapshotQuote("NVDA  260516C00140000");
    assertThat(q).isEmpty();
  }

  // --- Phase 2c.3 option premium: REST poll fan-out (replaces the dead msgpack options WS) ---

  /**
   * Provider wired to the MockWebServer REST snapshot path but with the auto-start poll suppressed,
   * so a test can drive {@link AlpacaMarketData#pollOnce} deterministically (one enqueued snapshot
   * per call) instead of racing the recurring scheduler task against the mock dispatcher queue.
   */
  private AlpacaMarketData premiumProvider(com.ohmytradeagent.marketdata.health.FeedHealth fh) {
    RestClient client =
        RestClient.builder()
            .baseUrl(server.url("/").toString().replaceAll("/$", ""))
            .defaultHeader("APCA-API-KEY-ID", "key-id-for-test")
            .defaultHeader("APCA-API-SECRET-KEY", "key-secret-for-test")
            .defaultHeader("Accept", "application/json")
            .build();
    AlpacaMarketDataProperties props =
        new AlpacaMarketDataProperties(
            server.url("/").toString().replaceAll("/$", ""),
            "wss://example.invalid/should-not-connect",
            "key-id-for-test",
            "key-secret-for-test",
            "",
            "",
            null);
    return new AlpacaMarketData(client, mapper, props, HttpClient.newHttpClient(), scheduler, fh) {
      @Override
      void startPremiumPoll(String occSymbol) {
        // no-op: tests drive pollOnce() directly so the scheduler never races the enqueued snapshot
      }
    };
  }

  private static com.ohmytradeagent.marketdata.health.FeedHealth newFeedHealth() {
    return new com.ohmytradeagent.marketdata.health.FeedHealth(
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
  }

  private static MockResponse optionSnapshot(String bid, String ask) {
    return optionSnapshotFor("NVDA260516C00140000", bid, ask);
  }

  /** Snapshot with an explicit quote timestamp — the resample guard keys on it. */
  private static MockResponse optionSnapshotAt(String bid, String ask, String stamp) {
    return new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            "{\"snapshots\":{\"NVDA260516C00140000\":{\"latestQuote\":{\"bp\":"
                + bid
                + ",\"ap\":"
                + ask
                + ",\"t\":\""
                + stamp
                + "\"}}}}");
  }

  private static MockResponse optionSnapshotFor(String compactOcc, String bid, String ask) {
    return new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            "{\"snapshots\":{\""
                + compactOcc
                + "\":{\"latestQuote\":{\"bp\":"
                + bid
                + ",\"ap\":"
                + ask
                + ",\"t\":\"2026-05-15T17:22:31.123Z\"}}}}");
  }

  // --- option-premium outlier guard (the F1 equity guard's sibling; premium had none) ---

  private static final String OCC = "NVDA  260516C00140000";
  private static final String T1 = "2026-05-15T17:22:31.100Z";
  private static final String T2 = "2026-05-15T17:22:33.100Z";
  private static final String T3 = "2026-05-15T17:22:35.100Z";

  @Test
  void resampledQuote_sameTimestamp_isNotASecondObservation() {
    // THE break that falsified a workflow-side debounce. pollOnce emits on EVERY poll with no
    // dedup, and the timestamp comes from the QUOTE, not the poll. When the NBBO stops updating —
    // definitionally what happens when a bid is withdrawn and nothing requotes — consecutive polls
    // return the SAME quote. Counting that as two agreeing observations proves nothing.
    AlpacaMarketData p = premiumProvider(newFeedHealth());
    CopyOnWriteArrayList<Tick> rx = new CopyOnWriteArrayList<>();
    p.subscribePremium(OCC, rx::add);

    server.enqueue(optionSnapshotAt("1.20", "1.30", T1));
    p.pollOnce(OCC);
    server.enqueue(optionSnapshotAt("1.20", "1.30", T1)); // identical quote, resampled
    p.pollOnce(OCC);

    assertThat(rx).hasSize(1);
  }

  @Test
  void withdrawnBid_isRejected() {
    // The realistic blown book. mid = (0 + 1.30)/2 = 0.65 is an arithmetic artifact, not a price
    // anything can be sold into — and it halves the premium in one tick.
    AlpacaMarketData p = premiumProvider(newFeedHealth());
    CopyOnWriteArrayList<Tick> rx = new CopyOnWriteArrayList<>();
    p.subscribePremium(OCC, rx::add);

    server.enqueue(optionSnapshotAt("1.20", "1.30", T1));
    p.pollOnce(OCC);
    // ask 2.00 with NO bid gives mid 1.00 — only 20% from the 1.25 reference, so it sails through
    // the outlier band. Only the no-bid check can reject it, which is what makes this test pin that
    // check rather than the band. (An earlier version used ask 1.30 -> mid 0.65, rejected by the
    // band regardless, so it passed with the bid check deleted.)
    server.enqueue(optionSnapshotAt("0", "2.00", T2));
    p.pollOnce(OCC);

    assertThat(rx).hasSize(1);
    assertThat(rx.get(0).premium()).isEqualByComparingTo("1.25");
  }

  @Test
  void uncorroboratedOutlier_isHeldAndNeverFansOut() {
    // 1.25 -> 0.30 is a 76% collapse, past the 35% band. Held, not emitted. The market then proves
    // it was a phantom by quoting normally again.
    AlpacaMarketData p = premiumProvider(newFeedHealth());
    CopyOnWriteArrayList<Tick> rx = new CopyOnWriteArrayList<>();
    p.subscribePremium(OCC, rx::add);

    server.enqueue(optionSnapshotAt("1.20", "1.30", T1));
    p.pollOnce(OCC);
    server.enqueue(optionSnapshotAt("0.25", "0.35", T2)); // phantom, out of band
    p.pollOnce(OCC);
    server.enqueue(optionSnapshotAt("1.20", "1.30", T3)); // back to reality
    p.pollOnce(OCC);

    assertThat(rx).hasSize(2);
    assertThat(rx.get(0).premium()).isEqualByComparingTo("1.25");
    assertThat(rx.get(1).premium()).isEqualByComparingTo("1.25");
  }

  @Test
  void corroboratedMove_isAdmittedOnePollLate() {
    // A genuine violent move must NOT be lost — only delayed by one poll. Out-of-band means held
    // for corroboration, not dropped.
    AlpacaMarketData p = premiumProvider(newFeedHealth());
    CopyOnWriteArrayList<Tick> rx = new CopyOnWriteArrayList<>();
    p.subscribePremium(OCC, rx::add);

    server.enqueue(optionSnapshotAt("1.20", "1.30", T1));
    p.pollOnce(OCC);
    server.enqueue(optionSnapshotAt("0.25", "0.35", T2)); // held
    p.pollOnce(OCC);
    server.enqueue(optionSnapshotAt("0.26", "0.34", T3)); // agrees with the CANDIDATE -> admitted
    p.pollOnce(OCC);

    assertThat(rx).hasSize(2);
    assertThat(rx.get(1).premium()).isEqualByComparingTo("0.30");
  }

  @Test
  void twoDisagreeingPhantomsDoNotCorroborateEachOther() {
    // The hole a workflow-side rule had: it required only that the second print also differ from
    // the stale reference, so any two outliers blessed each other. Agreement must be against the
    // CANDIDATE. 0.30 and 0.90 are both far from 1.25 AND far from each other -> neither admitted.
    AlpacaMarketData p = premiumProvider(newFeedHealth());
    CopyOnWriteArrayList<Tick> rx = new CopyOnWriteArrayList<>();
    p.subscribePremium(OCC, rx::add);

    server.enqueue(optionSnapshotAt("1.20", "1.30", T1));
    p.pollOnce(OCC);
    server.enqueue(optionSnapshotAt("0.25", "0.35", T2)); // candidate 0.30
    p.pollOnce(OCC);
    // 0.60 is 52% from the 1.25 reference AND 100% from the 0.30 candidate — genuinely far from
    // both. (0.90 would NOT work: it is only 28% from the reference, inside the band, so it is
    // legitimately accepted on the reference test and never reaches the corroboration branch.)
    server.enqueue(optionSnapshotAt("0.55", "0.65", T3));
    p.pollOnce(OCC);

    assertThat(rx).hasSize(1);
  }

  @Test
  void ordinaryFastMoveWithinTheBandIsNotDelayed() {
    // The band must not fight the real market: option premium routinely moves tens of percent
    // between 2s polls, and an equity-tight band would reject that constantly.
    AlpacaMarketData p = premiumProvider(newFeedHealth());
    CopyOnWriteArrayList<Tick> rx = new CopyOnWriteArrayList<>();
    p.subscribePremium(OCC, rx::add);

    server.enqueue(optionSnapshotAt("1.20", "1.30", T1));
    p.pollOnce(OCC);
    server.enqueue(optionSnapshotAt("1.55", "1.65", T2)); // +28%, inside the 35% band
    p.pollOnce(OCC);

    assertThat(rx).hasSize(2);
    assertThat(rx.get(1).premium()).isEqualByComparingTo("1.60");
  }

  @Test
  void rallyThenReverse_bothTicksReachTheWorkflow_soTheRunnerStillExits() {
    // The exact sequence from watchlistTimeStop_afterTarget_runnerExitsViaChandelier — the
    // PLAN-2026-07-23 Fork A regression test, which encodes "post-target the time-stop is ignored
    // and the trail governs the runner". On eod_force_flatten=false tenants that trail is the ONLY
    // automatic exit, so a filter that swallows either of these ticks strands a real-money runner.
    //
    // An earlier WORKFLOW-side version of this guard broke exactly here: it corroborated the
    // RATCHET, so the tick confirming a new high was the pullback itself, and min() then set the
    // peak to the reversal price instead of the high — loosening the giveback AND failing to fire.
    // A feed-layer guard cannot do that: it decides only whether a QUOTE IS REAL, and never touches
    // the peak. Both moves here are ordinary (+25%, -10%), well inside the band, so both pass
    // through untouched and the workflow sees precisely what it saw before the guard existed.
    AlpacaMarketData p = premiumProvider(newFeedHealth());
    CopyOnWriteArrayList<Tick> rx = new CopyOnWriteArrayList<>();
    p.subscribePremium(OCC, rx::add);

    server.enqueue(optionSnapshotAt("3.15", "3.25", T1)); // arm at 3.20
    p.pollOnce(OCC);
    server.enqueue(optionSnapshotAt("3.95", "4.05", T2)); // rally to 4.00, the high
    p.pollOnce(OCC);
    server.enqueue(optionSnapshotAt("3.55", "3.65", T3)); // reverse to 3.60, which must FIRE
    p.pollOnce(OCC);

    assertThat(rx).hasSize(3);
    assertThat(rx.get(1).premium()).isEqualByComparingTo("4.00");
    assertThat(rx.get(2).premium()).isEqualByComparingTo("3.60");
  }

  @Test
  void pollOnce_snapshot_fansOutTickWithBidAskMid_andMarksOptionConnected() {
    com.ohmytradeagent.marketdata.health.FeedHealth fh = newFeedHealth();
    AlpacaMarketData p = premiumProvider(fh);
    CopyOnWriteArrayList<Tick> rx = new CopyOnWriteArrayList<>();
    p.subscribePremium("NVDA  260516C00140000", rx::add);

    server.enqueue(optionSnapshot("1.20", "1.30"));
    p.pollOnce("NVDA  260516C00140000");

    assertThat(rx).hasSize(1);
    assertThat(rx.get(0).occSymbol()).isEqualTo("NVDA  260516C00140000");
    assertThat(rx.get(0).premium()).isEqualByComparingTo("1.25");
    assertThat(rx.get(0).bid()).isEqualByComparingTo("1.20");
    assertThat(rx.get(0).ask()).isEqualByComparingTo("1.30");
    assertThat(fh.connected(com.ohmytradeagent.marketdata.health.FeedHealth.Feed.OPTION)).isTrue();
  }

  @Test
  void pollOnce_unsubscribedSymbol_emitsNoTick() {
    com.ohmytradeagent.marketdata.health.FeedHealth fh = newFeedHealth();
    AlpacaMarketData p = premiumProvider(fh);
    CopyOnWriteArrayList<Tick> rx = new CopyOnWriteArrayList<>();
    p.subscribePremium("NVDA  260516C00140000", rx::add);

    // A snapshot for a symbol nobody subscribed to must reach no listeners.
    server.enqueue(optionSnapshot("1.20", "1.30"));
    p.pollOnce("AAPL  260516C00190000");

    assertThat(rx).isEmpty();
  }

  @Test
  void pollOnce_emptySnapshot_emitsNoTick_andDoesNotDisconnectOnSingleMiss() {
    com.ohmytradeagent.marketdata.health.FeedHealth fh = newFeedHealth();
    AlpacaMarketData p = premiumProvider(fh);
    CopyOnWriteArrayList<Tick> rx = new CopyOnWriteArrayList<>();
    p.subscribePremium("NVDA  260516C00140000", rx::add);

    // One good poll marks the feed connected.
    server.enqueue(optionSnapshot("1.20", "1.30"));
    p.pollOnce("NVDA  260516C00140000");
    assertThat(fh.connected(com.ohmytradeagent.marketdata.health.FeedHealth.Feed.OPTION)).isTrue();

    // A single empty snapshot: no tick, and one miss is below the disconnect threshold.
    server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"snapshots\":{}}"));
    p.pollOnce("NVDA  260516C00140000");

    assertThat(rx).hasSize(1); // still just the one good tick
    assertThat(fh.connected(com.ohmytradeagent.marketdata.health.FeedHealth.Feed.OPTION)).isTrue();
  }

  @Test
  void pollOnce_429_emitsNoTick() {
    com.ohmytradeagent.marketdata.health.FeedHealth fh = newFeedHealth();
    AlpacaMarketData p = premiumProvider(fh);
    CopyOnWriteArrayList<Tick> rx = new CopyOnWriteArrayList<>();
    p.subscribePremium("NVDA  260516C00140000", rx::add);

    // Rate-limited: snapshotQuote swallows the HTTP error -> no tick, the recurring task survives.
    server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"message\":\"rate limit\"}"));
    p.pollOnce("NVDA  260516C00140000");

    assertThat(rx).isEmpty();
  }

  @Test
  void pollOnce_repeatedFailures_marksOptionDisconnected() {
    com.ohmytradeagent.marketdata.health.FeedHealth fh = newFeedHealth();
    AlpacaMarketData p = premiumProvider(fh);
    p.subscribePremium("NVDA  260516C00140000", t -> {});

    // Connected after a good poll, then three straight misses crosses the disconnect threshold.
    server.enqueue(optionSnapshot("1.20", "1.30"));
    p.pollOnce("NVDA  260516C00140000");
    assertThat(fh.connected(com.ohmytradeagent.marketdata.health.FeedHealth.Feed.OPTION)).isTrue();

    for (int i = 0; i < 3; i++) {
      server.enqueue(new MockResponse().setResponseCode(503).setBody("{\"message\":\"down\"}"));
      p.pollOnce("NVDA  260516C00140000");
    }

    assertThat(fh.connected(com.ohmytradeagent.marketdata.health.FeedHealth.Feed.OPTION)).isFalse();
  }

  @Test
  void pollOnce_oneHealthyOcc_keepsOptionConnected_whileAnotherFailsPastThreshold() {
    com.ohmytradeagent.marketdata.health.FeedHealth fh = newFeedHealth();
    AlpacaMarketData p = premiumProvider(fh);
    p.subscribePremium("NVDA  260516C00140000", t -> {});
    p.subscribePremium("AAPL  260516C00190000", t -> {});

    // NVDA is healthy...
    server.enqueue(optionSnapshotFor("NVDA260516C00140000", "1.20", "1.30"));
    p.pollOnce("NVDA  260516C00140000");

    // ...while AAPL fails three straight polls (past the per-OCC threshold).
    for (int i = 0; i < 3; i++) {
      server.enqueue(new MockResponse().setResponseCode(503).setBody("{\"message\":\"down\"}"));
      p.pollOnce("AAPL  260516C00190000");
    }

    // One healthy contract proves the feed is alive: the aggregate gauge must stay connected. The
    // OPTION feed only flips disconnected when EVERY subscribed OCC is failing.
    assertThat(fh.connected(com.ohmytradeagent.marketdata.health.FeedHealth.Feed.OPTION)).isTrue();
  }

  /**
   * Lifecycle: the per-OCC poll starts once on the first subscriber and is cancelled only when the
   * last subscriber leaves. Overrides {@code startPremiumPoll}/{@code stopPremiumPoll} to count the
   * lifecycle without real scheduling or REST calls.
   */
  @Test
  void premiumPoll_startsOnFirstSubscriber_stopsOnLastUnsubscribe() {
    AtomicInteger starts = new AtomicInteger();
    AtomicInteger stops = new AtomicInteger();
    AlpacaMarketData p = lifecycleCountingProvider(starts, stops);

    Subscription s1 = p.subscribePremium("NVDA  260516C00140000", t -> {});
    Subscription s2 = p.subscribePremium("NVDA  260516C00140000", t -> {});
    assertThat(starts.get()).isEqualTo(1); // second subscriber does not start a second poll
    assertThat(stops.get()).isZero();

    s1.close();
    assertThat(stops.get()).isZero(); // s2 still open -> poll stays alive

    s2.close();
    assertThat(stops.get()).isEqualTo(1); // last subscriber gone -> poll cancelled
  }

  /**
   * Concurrency: 16 first-subscribers on the same symbol behind a barrier must start exactly one
   * poll — the {@code isEmpty()->add->startPremiumPoll} compound in {@code subscribePremium} is
   * held under the per-symbol lock.
   */
  @Test
  void subscribePremium_startsPollExactlyOnce_underConcurrentFirstSubscribers() throws Exception {
    AtomicInteger starts = new AtomicInteger();
    AlpacaMarketData p = lifecycleCountingProvider(starts, new AtomicInteger());

    int threads = 16;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      CyclicBarrier barrier = new CyclicBarrier(threads);
      CountDownLatch done = new CountDownLatch(threads);
      for (int i = 0; i < threads; i++) {
        pool.submit(
            () -> {
              try {
                barrier.await();
                p.subscribePremium("NVDA  260516C00140000", t -> {});
              } catch (Exception ignored) {
                // drain the latch so the assertion reports rather than hanging on done.await()
              } finally {
                done.countDown();
              }
            });
      }
      assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }

    assertThat(starts.get()).isEqualTo(1);
  }

  private AlpacaMarketData lifecycleCountingProvider(AtomicInteger starts, AtomicInteger stops) {
    RestClient client =
        RestClient.builder().baseUrl(server.url("/").toString().replaceAll("/$", "")).build();
    AlpacaMarketDataProperties props =
        new AlpacaMarketDataProperties(
            server.url("/").toString().replaceAll("/$", ""),
            "wss://example.invalid/should-not-connect",
            "key-id-for-test",
            "key-secret-for-test",
            "",
            "",
            null);
    return new AlpacaMarketData(client, mapper, props, HttpClient.newHttpClient(), scheduler) {
      @Override
      void startPremiumPoll(String occSymbol) {
        starts.incrementAndGet();
      }

      @Override
      void stopPremiumPoll(String occSymbol) {
        stops.incrementAndGet();
      }
    };
  }

  // --- Phase 2 (watchlist-trigger): equity stock-feed ---

  private AlpacaMarketData equityProvider() {
    AlpacaMarketDataProperties props =
        new AlpacaMarketDataProperties(
            server.url("/").toString().replaceAll("/$", ""),
            "wss://example.invalid/should-not-connect",
            "key-id-for-test",
            "key-secret-for-test",
            "",
            "iex", // stock-feed=iex => effective stock URL set => gate OPEN
            null);
    RestClient client = RestClient.builder().baseUrl(props.dataBaseUrl()).build();
    // Subclass to skip the real WS connect (subscribeEquity's first-subscriber path would otherwise
    // attempt to dial the derived wss URL); the fan-out is exercised via dispatchStockWsMessage.
    return new AlpacaMarketData(client, mapper, props, HttpClient.newHttpClient(), scheduler) {
      @Override
      void sendStockSubscribe(String ticker) {
        // no-op: test drives dispatchStockWsMessage directly
      }
    };
  }

  @Test
  void subscribeEquity_tradeRecord_fansOutTickWithLastPrice() {
    AlpacaMarketData eq = equityProvider();
    CopyOnWriteArrayList<Tick> received = new CopyOnWriteArrayList<>();
    eq.subscribeEquity("NVDA", received::add);

    eq.dispatchStockWsMessage(
        "[{\"T\":\"t\",\"S\":\"NVDA\",\"p\":140.12,\"t\":\"2026-06-20T13:31:00Z\"}]");

    assertThat(received).hasSize(1);
    assertThat(received.get(0).occSymbol()).isEqualTo("NVDA");
    assertThat(received.get(0).premium()).isEqualByComparingTo("140.12");
  }

  @Test
  void subscribeEquity_haltedOrStaleTrade_isDropped() {
    AlpacaMarketData eq = equityProvider();
    CopyOnWriteArrayList<Tick> received = new CopyOnWriteArrayList<>();
    eq.subscribeEquity("NVDA", received::add);

    // condition "H" = halt, "P" = prior reference/late
    eq.dispatchStockWsMessage(
        "[{\"T\":\"t\",\"S\":\"NVDA\",\"p\":140.12,\"t\":\"2026-06-20T13:31:00Z\",\"c\":[\"H\"]}]");
    eq.dispatchStockWsMessage(
        "[{\"T\":\"t\",\"S\":\"NVDA\",\"p\":140.50,\"t\":\"2026-06-20T13:31:01Z\",\"c\":[\"P\"]}]");

    assertThat(received).isEmpty();
  }

  @Test
  void subscribeEquity_quoteOrStatusRecord_emitsNoTick() {
    AlpacaMarketData eq = equityProvider();
    CopyOnWriteArrayList<Tick> received = new CopyOnWriteArrayList<>();
    eq.subscribeEquity("NVDA", received::add);

    eq.dispatchStockWsMessage(
        "[{\"T\":\"q\",\"S\":\"NVDA\",\"bp\":140.0,\"ap\":140.2,\"t\":\"2026-06-20T13:31:00Z\"}]");
    eq.dispatchStockWsMessage("[{\"T\":\"status\",\"S\":\"NVDA\",\"sc\":\"H\"}]");

    assertThat(received).isEmpty();
  }

  private AlpacaMarketData equityProviderRecording(
      com.ohmytradeagent.marketdata.health.FeedHealth feedHealth,
      java.util.List<String> subscribed) {
    AlpacaMarketDataProperties props =
        new AlpacaMarketDataProperties(
            server.url("/").toString().replaceAll("/$", ""),
            "wss://example.invalid/should-not-connect",
            "key-id-for-test",
            "key-secret-for-test",
            "",
            "iex",
            null);
    RestClient client = RestClient.builder().baseUrl(props.dataBaseUrl()).build();
    return new AlpacaMarketData(
        client, mapper, props, HttpClient.newHttpClient(), scheduler, feedHealth) {
      @Override
      void sendStockSubscribe(String ticker) {
        subscribed.add(ticker); // record instead of dialing a real WS
      }
    };
  }

  @Test
  void stockAuthenticatedFrame_marksConnectedAndResubscribesAllTickers() {
    com.ohmytradeagent.marketdata.health.FeedHealth fh =
        new com.ohmytradeagent.marketdata.health.FeedHealth(
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    CopyOnWriteArrayList<String> subscribed = new CopyOnWriteArrayList<>();
    AlpacaMarketData eq = equityProviderRecording(fh, subscribed);
    eq.subscribeEquity("NVDA", t -> {});
    eq.subscribeEquity("SPY", t -> {});
    subscribed.clear();
    assertThat(eq.stockAuthenticated).isFalse();
    assertThat(fh.connected(com.ohmytradeagent.marketdata.health.FeedHealth.Feed.EQUITY)).isFalse();

    eq.dispatchStockWsMessage("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");

    assertThat(eq.stockAuthenticated).isTrue();
    assertThat(fh.connected(com.ohmytradeagent.marketdata.health.FeedHealth.Feed.EQUITY)).isTrue();
    assertThat(subscribed).containsExactlyInAnyOrder("NVDA", "SPY");
  }

  @Test
  void stockConnectedGreeting_doesNotAuthenticate() {
    com.ohmytradeagent.marketdata.health.FeedHealth fh =
        new com.ohmytradeagent.marketdata.health.FeedHealth(
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    AlpacaMarketData eq = equityProviderRecording(fh, new CopyOnWriteArrayList<>());

    eq.dispatchStockWsMessage("[{\"T\":\"success\",\"msg\":\"connected\"}]");

    assertThat(eq.stockAuthenticated).isFalse();
    assertThat(fh.connected(com.ohmytradeagent.marketdata.health.FeedHealth.Feed.EQUITY)).isFalse();
  }

  @Test
  void stockErrorFrame_doesNotAuthenticate() {
    com.ohmytradeagent.marketdata.health.FeedHealth fh =
        new com.ohmytradeagent.marketdata.health.FeedHealth(
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    AlpacaMarketData eq = equityProviderRecording(fh, new CopyOnWriteArrayList<>());

    eq.dispatchStockWsMessage("[{\"T\":\"error\",\"code\":402,\"msg\":\"auth failed\"}]");

    assertThat(eq.stockAuthenticated).isFalse();
    assertThat(fh.connected(com.ohmytradeagent.marketdata.health.FeedHealth.Feed.EQUITY)).isFalse();
  }

  @Test
  void authAction_buildsMessageAuthFrameWithConfiguredCreds() throws Exception {
    AlpacaMarketData eq =
        equityProviderRecording(
            new com.ohmytradeagent.marketdata.health.FeedHealth(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
            new CopyOnWriteArrayList<>());

    com.fasterxml.jackson.databind.JsonNode auth = mapper.readTree(eq.authAction());

    assertThat(auth.get("action").asText()).isEqualTo("auth");
    assertThat(auth.get("key").asText()).isEqualTo("key-id-for-test");
    assertThat(auth.get("secret").asText()).isEqualTo("key-secret-for-test");
  }

  @Test
  void sendStockSubscribe_gatedUntilAuthenticated() {
    java.net.http.WebSocket fake = org.mockito.Mockito.mock(java.net.http.WebSocket.class);
    org.mockito.Mockito.when(
            fake.sendText(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean()))
        .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(fake));
    AlpacaMarketDataProperties props =
        new AlpacaMarketDataProperties(
            server.url("/").toString().replaceAll("/$", ""),
            "wss://example.invalid/should-not-connect",
            "key-id-for-test",
            "key-secret-for-test",
            "",
            "iex",
            null);
    RestClient client = RestClient.builder().baseUrl(props.dataBaseUrl()).build();
    AlpacaMarketData eq =
        new AlpacaMarketData(client, mapper, props, HttpClient.newHttpClient(), scheduler) {
          @Override
          java.net.http.WebSocket ensureStockWs() {
            return fake; // skip the real connect/auth; exercise the real sendStockSubscribe gate
          }
        };

    // Pre-auth: the real sendStockSubscribe must NOT send a subscribe frame.
    eq.sendStockSubscribe("NVDA");
    org.mockito.Mockito.verify(fake, org.mockito.Mockito.never())
        .sendText(
            org.mockito.ArgumentMatchers.contains("subscribe"),
            org.mockito.ArgumentMatchers.anyBoolean());

    // After the `authenticated` control frame, a subscribe IS sent.
    eq.dispatchStockWsMessage("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");
    eq.sendStockSubscribe("NVDA");
    org.mockito.Mockito.verify(fake, org.mockito.Mockito.atLeastOnce())
        .sendText(
            org.mockito.ArgumentMatchers.contains("\"subscribe\""),
            org.mockito.ArgumentMatchers.eq(true));
  }

  @Test
  void effectiveStockDataWsUrl_allowsIexAndSip_caseInsensitive() {
    assertThat(propsWithFeed("iex").effectiveStockDataWsUrl())
        .contains("wss://stream.data.alpaca.markets/v2/iex");
    assertThat(propsWithFeed("SIP").effectiveStockDataWsUrl())
        .contains("wss://stream.data.alpaca.markets/v2/sip");
  }

  @Test
  void effectiveStockDataWsUrl_unknownFeed_failsFast() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> propsWithFeed("bogus").effectiveStockDataWsUrl())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("iex")
        .hasMessageContaining("sip");
  }

  @Test
  void effectiveStockDataWsUrl_blankFeed_staysGated() {
    assertThat(propsWithFeed("").effectiveStockDataWsUrl()).isEmpty();
  }

  // --- Phase 1 (F1): single-tick gross/phantom outlier guard on the equity trigger feed ---

  private JsonNode tradeRec(String price) throws Exception {
    return mapper.readTree(
        "{\"T\":\"t\",\"S\":\"NVDA\",\"p\":" + price + ",\"t\":\"2026-07-30T13:31:00Z\"}");
  }

  @Test
  void recordToEquityTick_rejectsGrossOutlierPrint() throws Exception {
    // INCIDENT REPRO: seed ~196, then a lone 201.00 (2.55% > 2%) is a gross/phantom print.
    assertThat(provider.recordToEquityTick(tradeRec("196.00"))).isNotNull();
    assertThat(provider.recordToEquityTick(tradeRec("201.00"))).isNull();

    // The phantom reverted: a following ~196 print is in-band vs the UNCHANGED reference (196),
    // so it is accepted — proving the outlier never advanced lastAccepted.
    Tick revert = provider.recordToEquityTick(tradeRec("196.10"));
    assertThat(revert).isNotNull();
    assertThat(revert.premium()).isEqualByComparingTo("196.10");
  }

  @Test
  void recordToEquityTick_acceptsInBandMove() throws Exception {
    assertThat(provider.recordToEquityTick(tradeRec("196.00"))).isNotNull();

    Tick moved = provider.recordToEquityTick(tradeRec("199.00")); // 1.53% < 2%
    assertThat(moved).isNotNull();
    assertThat(moved.premium()).isEqualByComparingTo("199.00");

    // Reference advanced to 199: 200.50 is >2% vs the original 196 but in-band vs 199 -> accepted.
    Tick next = provider.recordToEquityTick(tradeRec("200.50"));
    assertThat(next).isNotNull();
    assertThat(next.premium()).isEqualByComparingTo("200.50");
  }

  @Test
  void recordToEquityTick_firstPrintSeedsReference() throws Exception {
    Tick first = provider.recordToEquityTick(tradeRec("196.00"));
    assertThat(first).isNotNull();
    assertThat(first.premium()).isEqualByComparingTo("196.00");

    // Seeded: a subsequent in-band print is accepted against the seeded reference.
    assertThat(provider.recordToEquityTick(tradeRec("196.50"))).isNotNull();
  }

  @Test
  void recordToEquityTick_rejectsNonPositivePrice() throws Exception {
    // A non-positive equity print is aberrant: dropped and NEVER seeded as the reference (seeding 0
    // would make deviation() divide-by-zero and silently blind the ticker's feed).
    assertThat(provider.recordToEquityTick(tradeRec("0.00"))).isNull();
    assertThat(provider.recordToEquityTick(tradeRec("-5.00"))).isNull();

    // The bad prints never became the reference: the first positive print still seeds and is
    // accepted, and subsequent in-band prints keep flowing (no divide-by-zero, feed not poisoned).
    Tick seed = provider.recordToEquityTick(tradeRec("196.00"));
    assertThat(seed).isNotNull();
    assertThat(seed.premium()).isEqualByComparingTo("196.00");
    assertThat(provider.recordToEquityTick(tradeRec("196.50"))).isNotNull();
  }

  @Test
  void recordToEquityTick_corroboratedGapAccepted() throws Exception {
    assertThat(provider.recordToEquityTick(tradeRec("196.00"))).isNotNull();
    // 201 is >2% vs 196 -> dropped and held as the pending candidate.
    assertThat(provider.recordToEquityTick(tradeRec("201.00"))).isNull();

    // 202 corroborates the pending 201 (within 2% of it) -> accepted, reference advances to 202.
    Tick corroborated = provider.recordToEquityTick(tradeRec("202.00"));
    assertThat(corroborated).isNotNull();
    assertThat(corroborated.premium()).isEqualByComparingTo("202.00");

    // No deadlock: a print in-band vs the advanced reference (202) is accepted.
    Tick next = provider.recordToEquityTick(tradeRec("203.00"));
    assertThat(next).isNotNull();
    assertThat(next.premium()).isEqualByComparingTo("203.00");
  }

  @Test
  void recordToEquityTick_rejectionEmitsObservabilityLog() throws Exception {
    ch.qos.logback.classic.Logger logbackLogger =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AlpacaMarketData.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logbackLogger.addAppender(appender);
    try {
      provider.recordToEquityTick(tradeRec("196.00"));
      provider.recordToEquityTick(tradeRec("201.00")); // rejected outlier
    } finally {
      logbackLogger.detachAppender(appender);
    }

    assertThat(appender.list)
        .anySatisfy(
            e -> {
              String msg = e.getFormattedMessage();
              assertThat(msg).contains("AUDIT stock-tick-outlier-rejected");
              assertThat(msg).contains("ticker=NVDA");
              assertThat(msg).contains("last=201");
              assertThat(msg).contains("ref=196");
              assertThat(msg).contains("devPct=");
            });
  }

  @Test
  void dispatchStockWsMessage_outlierNotFannedOut() {
    AlpacaMarketData eq = equityProvider();
    CopyOnWriteArrayList<Tick> received = new CopyOnWriteArrayList<>();
    eq.subscribeEquity("NVDA", received::add);

    eq.dispatchStockWsMessage(
        "[{\"T\":\"t\",\"S\":\"NVDA\",\"p\":196.00,\"t\":\"2026-07-30T13:31:00Z\"}]");
    // A lone >2% jump (phantom) must reach no subscriber.
    eq.dispatchStockWsMessage(
        "[{\"T\":\"t\",\"S\":\"NVDA\",\"p\":201.00,\"t\":\"2026-07-30T13:31:01Z\"}]");

    assertThat(received).hasSize(1);
    assertThat(received.get(0).premium()).isEqualByComparingTo("196.00");
  }

  private static AlpacaMarketDataProperties propsWithFeed(String feed) {
    return new AlpacaMarketDataProperties(
        "https://data.alpaca.markets",
        "wss://example.invalid/should-not-connect",
        "key-id-for-test",
        "key-secret-for-test",
        "",
        feed,
        null);
  }

  @Test
  void subscribeEquity_failsClosed_whenStockFeedUnconfigured() {
    // Both stock-data-ws-url and stock-feed blank => gate CLOSED: must throw, never connect.
    AlpacaMarketDataProperties props =
        new AlpacaMarketDataProperties(
            server.url("/").toString().replaceAll("/$", ""),
            "wss://example.invalid/should-not-connect",
            "key-id-for-test",
            "key-secret-for-test",
            "",
            "",
            null);
    RestClient client = RestClient.builder().baseUrl(props.dataBaseUrl()).build();
    AlpacaMarketData gated =
        new AlpacaMarketData(client, mapper, props, HttpClient.newHttpClient(), scheduler);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> gated.subscribeEquity("NVDA", t -> {}))
        .isInstanceOf(StockFeedGatedException.class)
        .hasMessageContaining("gated");
  }

  /**
   * Fragmented WS delivery: {@code onText} may arrive in pieces (last=false ... last=true). The
   * listener accumulation must reassemble one message's fragments and reset before the next, so a
   * subsequent complete message never sees the prior message's buffer. Drives {@link
   * AlpacaMarketData#accumulateFrame} (the shared listener accumulator) directly.
   */
  @Test
  void accumulateFrame_reassemblesFragments_andResetsBetweenMessages() {
    StringBuilder buf = new StringBuilder();

    // Message 1 split across three onText calls; only the final (last=true) yields a frame.
    assertThat(AlpacaMarketData.accumulateFrame(buf, "[{\"T\":\"t\",", false)).isNull();
    assertThat(AlpacaMarketData.accumulateFrame(buf, "\"S\":\"NVDA\",", false)).isNull();
    String frame1 =
        AlpacaMarketData.accumulateFrame(
            buf, "\"p\":140.12,\"t\":\"2026-06-20T13:31:00Z\"}]", true);
    assertThat(frame1)
        .isEqualTo("[{\"T\":\"t\",\"S\":\"NVDA\",\"p\":140.12,\"t\":\"2026-06-20T13:31:00Z\"}]");

    // Message 2 (single complete frame) must NOT carry any residue of message 1.
    String frame2 =
        AlpacaMarketData.accumulateFrame(
            buf, "[{\"T\":\"t\",\"S\":\"AAPL\",\"p\":3.21,\"t\":\"2026-06-20T13:31:01Z\"}]", true);
    assertThat(frame2)
        .isEqualTo("[{\"T\":\"t\",\"S\":\"AAPL\",\"p\":3.21,\"t\":\"2026-06-20T13:31:01Z\"}]");
    assertThat(buf.length()).isZero();
  }

  // --- #717 premium-feed liveness: a poll that emits nothing still proves the feed is alive ---

  @Test
  void premiumFeedStatus_onEmit_countsThePollAndStampsBothClocks() {
    AlpacaMarketData p = premiumProvider(newFeedHealth());
    CopyOnWriteArrayList<Tick> rx = new CopyOnWriteArrayList<>();
    p.subscribePremium(OCC, rx::add);

    server.enqueue(optionSnapshotAt("1.20", "1.30", T1));
    p.pollOnce(OCC);

    assertThat(rx).hasSize(1);
    PremiumFeedStatus st = p.premiumFeedStatus().get(OCC);
    assertThat(st).isNotNull();
    assertThat(st.occSymbol()).isEqualTo(OCC);
    assertThat(st.subscribers()).isEqualTo(1);
    assertThat(st.pollOkCount()).isEqualTo(1L);
    assertThat(st.lastPollOkAt()).isNotNull();
    assertThat(st.lastEmitAt()).isNotNull();
    assertThat(st.consecutiveFailures()).isZero();
  }

  /**
   * The discriminating case, and the whole reason the badge reads the POLL stamp: a resampled quote
   * is rejected by the guard so NO tick is emitted, but the snapshot succeeded — the feed is alive.
   * Asserted on counters, not clock deltas, so it cannot reproduce the #771 clock-boundary flake.
   */
  @Test
  void premiumFeedStatus_guardRejectedQuote_advancesPollButNotEmit() {
    AlpacaMarketData p = premiumProvider(newFeedHealth());
    CopyOnWriteArrayList<Tick> rx = new CopyOnWriteArrayList<>();
    p.subscribePremium(OCC, rx::add);

    server.enqueue(optionSnapshotAt("1.20", "1.30", T1));
    p.pollOnce(OCC);
    Instant emitStamp = p.premiumFeedStatus().get(OCC).lastEmitAt();

    // SAME quote timestamp -> one observation resampled, not two. Rejected, no fan-out.
    server.enqueue(optionSnapshotAt("1.20", "1.30", T1));
    p.pollOnce(OCC);

    assertThat(rx).hasSize(1);
    PremiumFeedStatus st = p.premiumFeedStatus().get(OCC);
    assertThat(st.pollOkCount()).isEqualTo(2L);
    assertThat(st.lastEmitAt()).isEqualTo(emitStamp);
    assertThat(st.consecutiveFailures()).isZero();
  }

  @Test
  void premiumFeedStatus_failedSnapshot_doesNotCountAsAGoodPoll() {
    AlpacaMarketData p = premiumProvider(newFeedHealth());
    CopyOnWriteArrayList<Tick> rx = new CopyOnWriteArrayList<>();
    p.subscribePremium(OCC, rx::add);

    server.enqueue(optionSnapshotAt("1.20", "1.30", T1));
    p.pollOnce(OCC);
    server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"snapshots\":{}}"));
    p.pollOnce(OCC);

    PremiumFeedStatus st = p.premiumFeedStatus().get(OCC);
    assertThat(st.pollOkCount()).isEqualTo(1L);
    assertThat(st.consecutiveFailures()).isEqualTo(1);
  }

  @Test
  void premiumFeedStatus_afterLastUnsubscribe_dropsTheContract() {
    AlpacaMarketData p = premiumProvider(newFeedHealth());
    CopyOnWriteArrayList<Tick> rx = new CopyOnWriteArrayList<>();
    Subscription sub = p.subscribePremium(OCC, rx::add);

    server.enqueue(optionSnapshotAt("1.20", "1.30", T1));
    p.pollOnce(OCC);
    assertThat(p.premiumFeedStatus()).containsKey(OCC);

    sub.close();

    assertThat(p.premiumFeedStatus()).doesNotContainKey(OCC);
  }
}
