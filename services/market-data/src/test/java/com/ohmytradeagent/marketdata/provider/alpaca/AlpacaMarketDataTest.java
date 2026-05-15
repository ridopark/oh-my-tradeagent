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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
            "key-secret-for-test");
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
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"snapshots\":{\"NVDA  260516C00140000\":{\"latestQuote\":"
                    + "{\"bp\":1.20,\"ap\":1.30,\"t\":\"2026-05-15T17:22:31.123Z\"}}}}"));

    Optional<Quote> q = provider.snapshotQuote("NVDA  260516C00140000");

    assertThat(q).isPresent();
    Quote quote = q.get();
    assertThat(quote.bid()).isEqualByComparingTo("1.20");
    assertThat(quote.ask()).isEqualByComparingTo("1.30");
    assertThat(quote.mid()).isEqualByComparingTo("1.25");

    RecordedRequest req = server.takeRequest();
    assertThat(req.getMethod()).isEqualTo("GET");
    assertThat(req.getPath()).startsWith("/v1beta1/options/snapshots");
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

  @Test
  void dispatchWsMessage_malformedFrame_isNoOp() {
    CopyOnWriteArrayList<Tick> received = new CopyOnWriteArrayList<>();
    provider.subscribePremium("NVDA  260516C00140000", received::add);

    provider.dispatchWsMessage("not json at all");
    provider.dispatchWsMessage("{\"not\":\"an array\"}");

    assertThat(received).isEmpty();
  }
}
