package com.ohmytradeagent.marketdata.provider.alpaca;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.marketdata.provider.Quote;
import com.ohmytradeagent.marketdata.provider.Subscription;
import com.ohmytradeagent.marketdata.provider.Tick;
import java.io.IOException;
import java.net.http.HttpClient;
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
 * path, and exercises the WS message handler directly via {@link
 * AlpacaMarketData#dispatchWsMessage} for the streaming fan-out (no real socket — the WS
 * integration test is intentionally deferred per Phase 2c.2 scope).
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
            "");
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

  @Test
  void dispatchWsMessage_tradeRecord_fansOutTickWithTradePrice() {
    CopyOnWriteArrayList<Tick> received = new CopyOnWriteArrayList<>();
    provider.subscribePremium("NVDA  260516C00140000", received::add);

    provider.dispatchWsMessage(
        "[{\"T\":\"t\",\"S\":\"NVDA  260516C00140000\",\"p\":1.45,\"t\":\"2026-05-15T17:25:00Z\"}]");

    assertThat(received).hasSize(1);
    assertThat(received.get(0).occSymbol()).isEqualTo("NVDA  260516C00140000");
    assertThat(received.get(0).premium()).isEqualByComparingTo("1.45");
  }

  @Test
  void dispatchWsMessage_quoteRecord_emitsMid() {
    CopyOnWriteArrayList<Tick> received = new CopyOnWriteArrayList<>();
    provider.subscribePremium("NVDA  260516C00140000", received::add);

    provider.dispatchWsMessage(
        "[{\"T\":\"q\",\"S\":\"NVDA  260516C00140000\",\"bp\":1.20,\"ap\":1.30,\"t\":\"2026-05-15T17:26:00Z\"}]");

    assertThat(received).hasSize(1);
    assertThat(received.get(0).premium()).isEqualByComparingTo("1.25");
  }

  @Test
  void dispatchWsMessage_unknownSymbol_dropsTick() {
    CopyOnWriteArrayList<Tick> received = new CopyOnWriteArrayList<>();
    provider.subscribePremium("NVDA  260516C00140000", received::add);

    provider.dispatchWsMessage(
        "[{\"T\":\"t\",\"S\":\"AAPL  260516C00190000\",\"p\":3.21,\"t\":\"2026-05-15T17:25:00Z\"}]");

    assertThat(received).isEmpty();
  }

  @Test
  void closeSubscription_stopsFanOut() {
    CopyOnWriteArrayList<Tick> received = new CopyOnWriteArrayList<>();
    Subscription sub = provider.subscribePremium("NVDA  260516C00140000", received::add);

    sub.close();

    provider.dispatchWsMessage(
        "[{\"T\":\"t\",\"S\":\"NVDA  260516C00140000\",\"p\":2.00,\"t\":\"2026-05-15T17:30:00Z\"}]");

    assertThat(received).isEmpty();
  }

  /**
   * Phase 2c.2 review feedback round 2 (Major 1): {@code AlpacaSubscription.close()} must not
   * evict-by-key a listener-list that a concurrent {@code subscribePremium} just re-inserted under
   * the same key. With the unconditional {@code bySymbol.remove(symbol)}, a re-subscribe after
   * close left the new listener orphaned (the next tick had no listeners under {@code symbol}). The
   * fix uses {@code remove(key, expectedValue)} so the eviction only fires when the value is still
   * our just-emptied list. This test models the simple sequential resubscribe path; an incoming
   * tick after the re-subscribe MUST reach the new listener.
   */
  @Test
  void subscribe_after_all_listeners_closed_receives_ticks() {
    CopyOnWriteArrayList<Tick> first = new CopyOnWriteArrayList<>();
    Subscription sub = provider.subscribePremium("NVDA  260516C00140000", first::add);
    sub.close();

    // Re-subscribe on the same symbol. With the old code, close() unconditionally evicted the
    // listener-list — but since close()'s eviction is sequenced before this subscribe, the
    // computeIfAbsent here creates a fresh list. The race the fix targets is when subscribe and
    // close interleave AND a third subscribe arrives between subscribe and close's eviction. The
    // simpler regression assertion is: after close+resubscribe, the new listener observes a tick.
    CopyOnWriteArrayList<Tick> second = new CopyOnWriteArrayList<>();
    provider.subscribePremium("NVDA  260516C00140000", second::add);

    provider.dispatchWsMessage(
        "[{\"T\":\"t\",\"S\":\"NVDA  260516C00140000\",\"p\":3.14,\"t\":\"2026-05-15T17:35:00Z\"}]");

    assertThat(first).isEmpty();
    assertThat(second).hasSize(1);
    assertThat(second.get(0).premium()).isEqualByComparingTo("3.14");
  }

  @Test
  void closeOneSubscription_otherStillReceives() {
    CopyOnWriteArrayList<Tick> rxA = new CopyOnWriteArrayList<>();
    CopyOnWriteArrayList<Tick> rxB = new CopyOnWriteArrayList<>();
    Subscription subA = provider.subscribePremium("NVDA  260516C00140000", rxA::add);
    provider.subscribePremium("NVDA  260516C00140000", rxB::add);

    subA.close();

    provider.dispatchWsMessage(
        "[{\"T\":\"t\",\"S\":\"NVDA  260516C00140000\",\"p\":2.10,\"t\":\"2026-05-15T17:31:00Z\"}]");

    assertThat(rxA).isEmpty();
    assertThat(rxB).hasSize(1);
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
            "iex"); // stock-feed=iex => effective stock URL set => gate OPEN
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

  private static AlpacaMarketDataProperties propsWithFeed(String feed) {
    return new AlpacaMarketDataProperties(
        "https://data.alpaca.markets",
        "wss://example.invalid/should-not-connect",
        "key-id-for-test",
        "key-secret-for-test",
        "",
        feed);
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
            "");
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

  /**
   * End-to-end through the dispatcher: reassembled fragments of one trade message fan out a single
   * tick, and a following message does not inherit the prior buffer.
   */
  @Test
  void fragmentedThenCompleteFrames_fanOutCorrectTicks() {
    CopyOnWriteArrayList<Tick> received = new CopyOnWriteArrayList<>();
    provider.subscribePremium("NVDA  260516C00140000", received::add);

    StringBuilder buf = new StringBuilder();
    assertThat(
            AlpacaMarketData.accumulateFrame(
                buf, "[{\"T\":\"t\",\"S\":\"NVDA  260516C00140000\",", false))
        .isNull();
    String frame1 = AlpacaMarketData.accumulateFrame(buf, "\"p\":1.45,\"t\":\"x\"}]", true);
    provider.dispatchWsMessage(frame1);

    String frame2 =
        AlpacaMarketData.accumulateFrame(
            buf, "[{\"T\":\"t\",\"S\":\"NVDA  260516C00140000\",\"p\":1.50,\"t\":\"y\"}]", true);
    provider.dispatchWsMessage(frame2);

    assertThat(received).hasSize(2);
    assertThat(received.get(0).premium()).isEqualByComparingTo("1.45");
    assertThat(received.get(1).premium()).isEqualByComparingTo("1.50");
  }

  @Test
  void dispatchWsMessage_malformedFrame_isNoOp() {
    CopyOnWriteArrayList<Tick> received = new CopyOnWriteArrayList<>();
    provider.subscribePremium("NVDA  260516C00140000", received::add);

    provider.dispatchWsMessage("not json at all");
    provider.dispatchWsMessage("{\"not\":\"an array\"}");

    assertThat(received).isEmpty();
  }

  /**
   * Phase 2c.2 review feedback (Major 1): {@code subscribePremium} must atomically check-add-emit
   * the upstream subscribe so two concurrent first-subscribers on the same symbol do not both issue
   * {@code sendSubscribe}. Drives 16 threads at the same symbol behind a barrier; subclasses {@link
   * AlpacaMarketData} to count {@code sendSubscribe} invocations on the symbol. Without the
   * synchronized block in {@code subscribePremium}, multiple threads see {@code isEmpty()==true}
   * and the count exceeds 1.
   */
  @Test
  void subscribePremium_isAtomicAcrossConcurrentFirstSubscribers() throws Exception {
    AtomicInteger sendSubscribeCount = new AtomicInteger();
    AlpacaMarketDataProperties props =
        new AlpacaMarketDataProperties(
            server.url("/").toString().replaceAll("/$", ""),
            "wss://example.invalid/should-not-connect",
            "key-id-for-test",
            "key-secret-for-test",
            "",
            "");
    RestClient client = RestClient.builder().baseUrl(props.dataBaseUrl()).build();
    AlpacaMarketData countingProvider =
        new AlpacaMarketData(client, mapper, props, HttpClient.newHttpClient(), scheduler) {
          @Override
          void sendSubscribe(String occSymbol) {
            sendSubscribeCount.incrementAndGet();
            // Skip the real WS path — counting the call is sufficient and avoids the DNS
            // lookup against wss://example.invalid that the real path would attempt.
          }
        };

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
                countingProvider.subscribePremium("NVDA  260516C00140000", t -> {});
              } catch (Exception ignored) {
                // Per-thread failures still allow the latch to drain so the test reports the
                // count assertion rather than hanging on done.await().
              } finally {
                done.countDown();
              }
            });
      }
      assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }

    assertThat(sendSubscribeCount.get()).isEqualTo(1);
  }

  /**
   * Phase 2c.2 review feedback (Major 2): the reconnect runnable must null the socket BEFORE
   * clearing the reconnect-in-flight guard so that a stale {@code WsListener.onClose} firing
   * mid-reconnect sees {@code reconnectInFlight=true} and {@link
   * java.util.concurrent.atomic.AtomicBoolean#compareAndSet compareAndSet} fails — preventing a
   * spurious second reconnect.
   *
   * <p>The test simulates that race by, while a reconnect runnable is queued on the scheduler (flag
   * set, body not yet run), firing a concurrent {@code scheduleReconnect()} the way a stale onClose
   * would. With the {@link java.util.concurrent.atomic.AtomicBoolean#compareAndSet compareAndSet}
   * guard, this MUST be a no-op — exactly one task on the scheduler.
   */
  @Test
  void scheduleReconnect_doesNotRaceWithConcurrentOnClose() throws Exception {
    AlpacaMarketDataProperties props =
        new AlpacaMarketDataProperties(
            server.url("/").toString().replaceAll("/$", ""),
            "wss://example.invalid/should-not-connect",
            "key-id-for-test",
            "key-secret-for-test",
            "",
            "");
    RestClient client = RestClient.builder().baseUrl(props.dataBaseUrl()).build();

    AtomicInteger scheduledTasks = new AtomicInteger();
    ScheduledExecutorService countingScheduler =
        new java.util.concurrent.ScheduledThreadPoolExecutor(1) {
          @Override
          public java.util.concurrent.ScheduledFuture<?> schedule(
              Runnable command, long delay, TimeUnit unit) {
            scheduledTasks.incrementAndGet();
            // Park the runnable far in the future — the test drives runReconnect() directly.
            return super.schedule(() -> {}, 1_000_000, unit);
          }
        };

    try {
      AlpacaMarketData provider =
          new AlpacaMarketData(
              client, mapper, props, HttpClient.newHttpClient(), countingScheduler) {
            @Override
            java.net.http.WebSocket ensureWs() {
              // Skip real WS connect to wss://example.invalid. Returning null exercises the
              // "WS not yet open" branch in runReconnect without triggering a recursive
              // scheduleReconnect on connect failure.
              return null;
            }

            @Override
            void sendSubscribe(String occSymbol) {
              // No-op: bySymbol is empty in this test.
            }
          };

      // First scheduleReconnect raises the flag and enqueues one runnable.
      provider.scheduleReconnect();
      assertThat(scheduledTasks.get()).isEqualTo(1);

      // A stale onClose firing during the in-flight window calls scheduleReconnect again. The
      // compareAndSet guard must reject it — no second runnable scheduled.
      provider.scheduleReconnect();
      assertThat(scheduledTasks.get())
          .as("stale-onClose scheduleReconnect during in-flight window must be a no-op")
          .isEqualTo(1);

      // Drive the runnable body directly. With the fix (B′ before A′), this body nulls ws BEFORE
      // clearing the flag; a stale onClose racing the body never observes the inconsistent
      // state of {flag=false, ws=non-null}.
      provider.runReconnect();

      // After runReconnect completes, the flag is cleared and a fresh scheduleReconnect IS
      // allowed to enqueue.
      provider.scheduleReconnect();
      assertThat(scheduledTasks.get())
          .as("after runReconnect completes, fresh scheduleReconnect should enqueue")
          .isEqualTo(2);
    } finally {
      countingScheduler.shutdownNow();
    }
  }
}
