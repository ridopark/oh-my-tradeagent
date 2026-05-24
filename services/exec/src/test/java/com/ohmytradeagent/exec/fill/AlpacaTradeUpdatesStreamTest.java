package com.ohmytradeagent.exec.fill;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ohmytradeagent.exec.broker.alpaca.AlpacaProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.Assertions;
import org.java_websocket.WebSocket;
import org.java_websocket.enums.Opcode;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link AlpacaTradeUpdatesStream} against an in-process Java-WebSocket server. Pins the
 * Alpaca trade-updates handshake shape (authenticate → listen → events) and the listener's filter /
 * dedup / reconnect contracts.
 */
class AlpacaTradeUpdatesStreamTest {

  private static final long AWAIT_MS = 3_000L;

  private RecordingWsServer server;
  private int port;
  private AlpacaTradeUpdatesStream stream;
  private RecordingDispatcher dispatcher;
  private FillListenerMetrics metrics;
  private SimpleMeterRegistry registry;

  @BeforeEach
  void setUp() throws Exception {
    port = findFreePort();
    server = new RecordingWsServer(port);
    server.setReuseAddr(true);
    server.start();
    server.awaitStarted(AWAIT_MS, TimeUnit.MILLISECONDS);

    dispatcher = new RecordingDispatcher();
    registry = new SimpleMeterRegistry();
    metrics = new FillListenerMetrics(registry);

    FillListenerProperties props =
        new FillListenerProperties(true, "ws://localhost:" + port + "/stream", 100L, 1_000L, 32);
    AlpacaProperties alpaca = new AlpacaProperties("http://unused", "test-key", "test-secret");
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    stream = new AlpacaTradeUpdatesStream(props, alpaca, dispatcher, metrics, mapper);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (stream != null) {
      stream.stop();
    }
    if (server != null) {
      server.stop(500);
    }
  }

  @Test
  void sendsAuthAndListenFramesOnConnect() throws Exception {
    stream.start();

    String auth = server.frames.poll(AWAIT_MS, TimeUnit.MILLISECONDS);
    String listen = server.frames.poll(AWAIT_MS, TimeUnit.MILLISECONDS);

    assertThat(auth).isNotNull();
    assertThat(auth).contains("\"action\":\"authenticate\"");
    assertThat(auth).contains("\"key_id\":\"test-key\"");
    assertThat(auth).contains("\"secret_key\":\"test-secret\"");
    assertThat(listen).isNotNull();
    assertThat(listen).contains("\"action\":\"listen\"");
    assertThat(listen).contains("\"trade_updates\"");
  }

  @Test
  void fillEventReachesDispatcher() throws Exception {
    stream.start();
    awaitHandshake();

    server.broadcast(
        "{\"stream\":\"trade_updates\",\"data\":{\"event\":\"fill\","
            + "\"order\":{\"id\":\"brk-1\",\"client_order_id\":\"ck-1\","
            + "\"filled_qty\":\"5\",\"filled_avg_price\":\"0.84\","
            + "\"filled_at\":\"2026-05-19T17:08:11Z\"}}}");

    BrokerFillEvent fill = dispatcher.events.poll(AWAIT_MS, TimeUnit.MILLISECONDS);
    assertThat(fill).isNotNull();
    assertThat(fill.brokerOrderId()).isEqualTo("brk-1");
    assertThat(fill.clientOrderId()).isEqualTo("ck-1");
    assertThat(fill.filledQty()).isEqualTo(5L);
    assertThat(fill.avgFillPrice()).isEqualByComparingTo(new BigDecimal("0.84"));
    assertThat(fill.filledAt()).isEqualTo(OffsetDateTime.parse("2026-05-19T17:08:11Z"));
    assertThat(fill.source()).isEqualTo(BrokerFillEvent.Source.WS);

    assertThat(registry.counter("fill_listener.events_received", "event", "fill").count())
        .isEqualTo(1.0);
    assertThat(registry.counter("fill_listener.events_dispatched").count()).isEqualTo(1.0);
  }

  @Test
  void newEventIsFilteredOut() throws Exception {
    stream.start();
    awaitHandshake();

    server.broadcast(
        "{\"stream\":\"trade_updates\",\"data\":{\"event\":\"new\","
            + "\"order\":{\"id\":\"brk-x\",\"client_order_id\":\"ck-x\"}}}");

    BrokerFillEvent never = dispatcher.events.poll(500, TimeUnit.MILLISECONDS);
    assertThat(never).isNull();
    assertThat(registry.counter("fill_listener.events_dispatched").count()).isEqualTo(0.0);
  }

  @Test
  void duplicateFillIsDedupped() throws Exception {
    stream.start();
    awaitHandshake();

    String payload =
        "{\"stream\":\"trade_updates\",\"data\":{\"event\":\"fill\","
            + "\"order\":{\"id\":\"brk-d\",\"client_order_id\":\"ck-d\","
            + "\"filled_qty\":\"3\",\"filled_avg_price\":\"1.10\","
            + "\"filled_at\":\"2026-05-19T18:00:00Z\"}}}";
    server.broadcast(payload);
    server.broadcast(payload);

    BrokerFillEvent first = dispatcher.events.poll(AWAIT_MS, TimeUnit.MILLISECONDS);
    assertThat(first).isNotNull();
    BrokerFillEvent second = dispatcher.events.poll(500, TimeUnit.MILLISECONDS);
    assertThat(second).isNull();
    assertThat(registry.counter("fill_listener.events_dropped_dedup").count()).isEqualTo(1.0);
  }

  @Test
  void reconnectsAfterServerClose() throws Exception {
    stream.start();
    awaitHandshake();

    server.closeAllClients();

    // After server closes the client side, the listener should reconnect within
    // reconnectBaseMs (100ms) + a re-handshake. Wait for new auth+listen frames.
    String auth = server.frames.poll(AWAIT_MS, TimeUnit.MILLISECONDS);
    String listen = server.frames.poll(AWAIT_MS, TimeUnit.MILLISECONDS);
    assertThat(auth).isNotNull();
    assertThat(listen).isNotNull();
    assertThat(registry.counter("fill_listener.reconnects").count()).isGreaterThanOrEqualTo(1.0);
  }

  @Test
  void lastEventAgeGaugeIsRegistered() {
    assertThat(registry.find("fill_listener.last_event_age_seconds").gauge()).isNotNull();
  }

  @Test
  void malformedJsonIsSwallowed() throws Exception {
    stream.start();
    awaitHandshake();

    server.broadcast("not json at all {{{");
    server.broadcast(
        "{\"stream\":\"trade_updates\",\"data\":{\"event\":\"fill\","
            + "\"order\":{\"id\":\"brk-ok\",\"client_order_id\":\"ck-ok\","
            + "\"filled_qty\":\"1\",\"filled_avg_price\":\"2.00\","
            + "\"filled_at\":\"2026-05-19T19:00:00Z\"}}}");

    BrokerFillEvent fill = dispatcher.events.poll(AWAIT_MS, TimeUnit.MILLISECONDS);
    assertThat(fill).isNotNull();
    assertThat(fill.brokerOrderId()).isEqualTo("brk-ok");
  }

  @Test
  void multiFrameTextIsReassembled() throws Exception {
    stream.start();
    awaitHandshake();

    String payload =
        "{\"stream\":\"trade_updates\",\"data\":{\"event\":\"fill\","
            + "\"order\":{\"id\":\"brk-mf\",\"client_order_id\":\"ck-mf\","
            + "\"filled_qty\":\"2\",\"filled_avg_price\":\"3.30\","
            + "\"filled_at\":\"2026-05-19T20:00:00Z\"}}}";
    int mid = payload.length() / 2;
    server.sendFragmented(payload.substring(0, mid), payload.substring(mid));

    BrokerFillEvent fill = dispatcher.events.poll(AWAIT_MS, TimeUnit.MILLISECONDS);
    assertThat(fill).isNotNull();
    assertThat(fill.brokerOrderId()).isEqualTo("brk-mf");
    assertThat(fill.filledQty()).isEqualTo(2L);
  }

  @Test
  void missingRequiredFieldIsSkipped() throws Exception {
    stream.start();
    awaitHandshake();

    server.broadcast(
        "{\"stream\":\"trade_updates\",\"data\":{\"event\":\"fill\","
            + "\"order\":{\"id\":\"brk-mf2\",\"client_order_id\":\"ck-mf2\","
            + "\"filled_qty\":\"5\",\"filled_at\":\"2026-05-19T21:00:00Z\"}}}");

    BrokerFillEvent never = dispatcher.events.poll(500, TimeUnit.MILLISECONDS);
    assertThat(never).isNull();
    assertThat(registry.counter("fill_listener.events_dispatched").count()).isEqualTo(0.0);
  }

  @Test
  void dispatcherThrowingDoesNotBreakStream() throws Exception {
    java.util.concurrent.atomic.AtomicBoolean firstCallThrows =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    java.util.concurrent.BlockingQueue<BrokerFillEvent> seen =
        new java.util.concurrent.LinkedBlockingQueue<>();
    FillDispatcher throwing =
        event -> {
          if (firstCallThrows.getAndSet(false)) {
            throw new RuntimeException("boom");
          }
          seen.add(event);
        };
    stream =
        new AlpacaTradeUpdatesStream(
            new FillListenerProperties(
                true, "ws://localhost:" + port + "/stream", 100L, 1_000L, 32),
            new com.ohmytradeagent.exec.broker.alpaca.AlpacaProperties(
                "http://unused", "test-key", "test-secret"),
            throwing,
            metrics,
            new ObjectMapper().registerModule(new JavaTimeModule()));
    stream.start();
    awaitHandshake();

    server.broadcast(
        "{\"stream\":\"trade_updates\",\"data\":{\"event\":\"fill\","
            + "\"order\":{\"id\":\"brk-boom\",\"client_order_id\":\"ck-boom\","
            + "\"filled_qty\":\"1\",\"filled_avg_price\":\"0.50\","
            + "\"filled_at\":\"2026-05-19T22:00:00Z\"}}}");
    server.broadcast(
        "{\"stream\":\"trade_updates\",\"data\":{\"event\":\"fill\","
            + "\"order\":{\"id\":\"brk-after\",\"client_order_id\":\"ck-after\","
            + "\"filled_qty\":\"4\",\"filled_avg_price\":\"0.60\","
            + "\"filled_at\":\"2026-05-19T22:00:01Z\"}}}");

    BrokerFillEvent second = seen.poll(AWAIT_MS, TimeUnit.MILLISECONDS);
    assertThat(second).isNotNull();
    assertThat(second.brokerOrderId()).isEqualTo("brk-after");
  }

  private void awaitHandshake() throws InterruptedException {
    String auth = server.frames.poll(AWAIT_MS, TimeUnit.MILLISECONDS);
    String listen = server.frames.poll(AWAIT_MS, TimeUnit.MILLISECONDS);
    Assertions.assertThat(auth).isNotNull();
    Assertions.assertThat(listen).isNotNull();
  }

  private static int findFreePort() throws Exception {
    try (ServerSocket s = new ServerSocket(0)) {
      return s.getLocalPort();
    }
  }

  /** Java-WebSocket server fixture that records inbound frames and broadcasts test events. */
  private static class RecordingWsServer extends WebSocketServer {
    final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
    final List<WebSocket> clients = new ArrayList<>();
    private final CountDownLatch started = new CountDownLatch(1);

    RecordingWsServer(int port) {
      super(new InetSocketAddress(port));
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
      synchronized (clients) {
        clients.add(conn);
      }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
      synchronized (clients) {
        clients.remove(conn);
      }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
      frames.add(message);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
      // noisy under test teardown; ignore unless debugging
    }

    @Override
    public void onStart() {
      started.countDown();
    }

    void awaitStarted(long timeout, TimeUnit unit) throws InterruptedException {
      if (!started.await(timeout, unit)) {
        throw new IllegalStateException(
            "WS test server did not start within " + timeout + " " + unit);
      }
    }

    void closeAllClients() {
      List<WebSocket> snapshot;
      synchronized (clients) {
        snapshot = new ArrayList<>(clients);
        clients.clear();
      }
      for (WebSocket c : snapshot) {
        c.close();
      }
    }

    /**
     * Sends a text payload split into two WS frames (first fin=false, second fin=true). The
     * Java-WebSocket API requires both calls to pass the same {@code Opcode.TEXT}; it handles
     * promoting the second to a continuation frame internally.
     */
    void sendFragmented(String first, String second) {
      List<WebSocket> snapshot;
      synchronized (clients) {
        snapshot = new ArrayList<>(clients);
      }
      for (WebSocket c : snapshot) {
        c.sendFragmentedFrame(
            Opcode.TEXT, ByteBuffer.wrap(first.getBytes(StandardCharsets.UTF_8)), false);
        c.sendFragmentedFrame(
            Opcode.TEXT, ByteBuffer.wrap(second.getBytes(StandardCharsets.UTF_8)), true);
      }
    }
  }

  private static class RecordingDispatcher implements FillDispatcher {
    final BlockingQueue<BrokerFillEvent> events = new LinkedBlockingQueue<>();

    @Override
    public void dispatch(BrokerFillEvent event) {
      events.add(event);
    }
  }
}
