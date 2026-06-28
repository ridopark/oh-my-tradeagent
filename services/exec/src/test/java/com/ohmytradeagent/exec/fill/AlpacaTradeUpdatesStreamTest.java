package com.ohmytradeagent.exec.fill;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ohmytradeagent.exec.broker.BrokerCredentialSource;
import com.ohmytradeagent.exec.broker.BrokerCredentials;
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
import java.util.Map;
import java.util.Set;
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
        new FillListenerProperties(
            true, "ws://localhost:" + port + "/stream", false, 100L, 1_000L, 32);
    AlpacaProperties alpaca = new AlpacaProperties("http://unused", "test-key", "test-secret");
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    // OFF path: the credential source is unused (single socket authenticates with alpacaProps).
    stream =
        new AlpacaTradeUpdatesStream(
            props, alpaca, new ThrowingCredentialSource(), dispatcher, metrics, mapper);
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
    // events_dispatched_total is now bumped inside FillDispatcherImpl (not the listener), so
    // tests using a RecordingDispatcher don't trip it. See FillDispatcherImplTest for the
    // counter's success-path assertion.
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
  void backoffResetsAfterSuccessfulConnection() throws Exception {
    // Regression: runForever previously doubled backoff every iteration without ever resetting,
    // so a long-lived stream that disconnects normally would sleep at reconnectCapMs before
    // retrying — even though the network is fine. Drive 5 successful close/reconnect cycles and
    // assert the total elapsed time stays in the "base × N" regime (~500ms with reset) instead
    // of "geometric series up to cap" (~2500ms without).
    stream.start();
    awaitHandshake();

    long start = System.currentTimeMillis();
    for (int i = 0; i < 5; i++) {
      server.closeAllClients();
      String auth = server.frames.poll(AWAIT_MS, TimeUnit.MILLISECONDS);
      String listen = server.frames.poll(AWAIT_MS, TimeUnit.MILLISECONDS);
      assertThat(auth).as("auth frame for reconnect %d", i + 1).isNotNull();
      assertThat(listen).as("listen frame for reconnect %d", i + 1).isNotNull();
    }
    long elapsed = System.currentTimeMillis() - start;

    assertThat(elapsed)
        .as("5 reconnect cycles at base=100ms should complete in well under 1500ms with reset")
        .isLessThan(1500L);
    assertThat(registry.counter("fill_listener.reconnects").count()).isGreaterThanOrEqualTo(5.0);
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
                true, "ws://localhost:" + port + "/stream", false, 100L, 1_000L, 32),
            new com.ohmytradeagent.exec.broker.alpaca.AlpacaProperties(
                "http://unused", "test-key", "test-secret"),
            new ThrowingCredentialSource(),
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

  // ---------------------------------------------------------------------------------------------
  // Phase G — per-tenant path (exec.fill-listener.per-tenant.enabled = true)
  // ---------------------------------------------------------------------------------------------

  @Test
  void perTenantOpensOneSocketPerTenantWithThatTenantsCreds() throws Exception {
    // Two tenants, both pointing at the same loopback server (so the single RecordingWsServer
    // collects both auth handshakes), each with DISTINCT resolved creds. Assert both tenants'
    // keys authenticate — and NOT the pod-wide alpacaProps "test-key".
    String url = "ws://localhost:" + port + "/stream";
    MapCredentialSource creds =
        new MapCredentialSource(
            Map.of(
                "alice", new BrokerCredentials("alice-key", "alice-secret", "", url, ""),
                "bob", new BrokerCredentials("bob-key", "bob-secret", "", url, "")));
    stream = perTenantStream(creds);
    stream.start();

    // 2 tenants × (auth + listen) = 4 frames.
    Set<String> auths = new java.util.HashSet<>();
    for (int i = 0; i < 4; i++) {
      String frame = server.frames.poll(AWAIT_MS, TimeUnit.MILLISECONDS);
      assertThat(frame).isNotNull();
      if (frame.contains("\"authenticate\"")) {
        auths.add(frame);
      }
    }
    assertThat(auths).hasSize(2);
    String joined = String.join("\n", auths);
    assertThat(joined).contains("\"key_id\":\"alice-key\"");
    assertThat(joined).contains("\"key_id\":\"bob-key\"");
    assertThat(joined).doesNotContain("test-key");
  }

  @Test
  void perTenantSkipsTenantWhoseCredsFailButKeepsOthers() throws Exception {
    // alice resolves fine; "broken" throws on resolve. Independent supervision: alice's socket
    // still authenticates while broken's is skipped (logged), never opened.
    String url = "ws://localhost:" + port + "/stream";
    MapCredentialSource creds =
        new MapCredentialSource(
            Map.of("alice", new BrokerCredentials("alice-key", "alice-secret", "", url, "")));
    creds.enumerate(
        "alice", "broken"); // both enumerated, but "broken" has no entry → resolve throws
    stream = perTenantStream(creds);
    stream.start();

    Set<String> auths = new java.util.HashSet<>();
    String f1 = server.frames.poll(AWAIT_MS, TimeUnit.MILLISECONDS);
    String f2 = server.frames.poll(AWAIT_MS, TimeUnit.MILLISECONDS);
    for (String f : new String[] {f1, f2}) {
      if (f != null && f.contains("\"authenticate\"")) {
        auths.add(f);
      }
    }
    assertThat(auths).hasSize(1);
    assertThat(auths.iterator().next()).contains("\"key_id\":\"alice-key\"");
    // "broken" never authenticated — no third client connected.
    assertThat(server.frames.poll(500, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test
  void perTenantBlankCredsSkippedNotInfiniteBlankAuth() throws Exception {
    // A blank-key/secret resolution must be SKIPPED at startup, not spun into a blank-auth storm.
    String url = "ws://localhost:" + port + "/stream";
    MapCredentialSource creds =
        new MapCredentialSource(Map.of("blankguy", new BrokerCredentials("", "", "", url, "")));
    stream = perTenantStream(creds);
    stream.start();

    // No socket should ever authenticate (the only tenant has blank creds).
    assertThat(server.frames.poll(800, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test
  void perTenantDedupIsTenantScoped() throws Exception {
    // Same broker_order_id arriving on TWO different tenants' sockets must BOTH dispatch (a
    // broker_order_id is only unique per account); a duplicate WITHIN one tenant is deduped.
    // Two separate servers so a broadcast reaches only one tenant's socket.
    int portB = findFreePort();
    RecordingWsServer serverB = new RecordingWsServer(portB);
    serverB.setReuseAddr(true);
    serverB.start();
    serverB.awaitStarted(AWAIT_MS, TimeUnit.MILLISECONDS);
    try {
      String urlA = "ws://localhost:" + port + "/stream";
      String urlB = "ws://localhost:" + portB + "/stream";
      MapCredentialSource creds =
          new MapCredentialSource(
              Map.of(
                  "alice", new BrokerCredentials("alice-key", "alice-secret", "", urlA, ""),
                  "bob", new BrokerCredentials("bob-key", "bob-secret", "", urlB, "")));
      stream = perTenantStream(creds);
      stream.start();
      // Wait for both handshakes (4 frames across the two servers).
      assertThat(server.frames.poll(AWAIT_MS, TimeUnit.MILLISECONDS)).isNotNull();
      assertThat(server.frames.poll(AWAIT_MS, TimeUnit.MILLISECONDS)).isNotNull();
      assertThat(serverB.frames.poll(AWAIT_MS, TimeUnit.MILLISECONDS)).isNotNull();
      assertThat(serverB.frames.poll(AWAIT_MS, TimeUnit.MILLISECONDS)).isNotNull();

      String sharedId = "brk-shared";
      String payload =
          "{\"stream\":\"trade_updates\",\"data\":{\"event\":\"fill\","
              + "\"order\":{\"id\":\""
              + sharedId
              + "\",\"client_order_id\":\"ck\","
              + "\"filled_qty\":\"5\",\"filled_avg_price\":\"0.84\","
              + "\"filled_at\":\"2026-05-19T17:08:11Z\"}}}";
      // alice gets it once, bob gets the SAME broker_order_id once → both dispatched.
      server.broadcast(payload);
      serverB.broadcast(payload);

      BrokerFillEvent first = dispatcher.events.poll(AWAIT_MS, TimeUnit.MILLISECONDS);
      BrokerFillEvent second = dispatcher.events.poll(AWAIT_MS, TimeUnit.MILLISECONDS);
      assertThat(first).isNotNull();
      assertThat(second).isNotNull();
      assertThat(first.brokerOrderId()).isEqualTo(sharedId);
      assertThat(second.brokerOrderId()).isEqualTo(sharedId);

      // A duplicate WITHIN alice's socket is deduped → no third dispatch.
      server.broadcast(payload);
      assertThat(dispatcher.events.poll(700, TimeUnit.MILLISECONDS)).isNull();
    } finally {
      serverB.stop(500);
    }
  }

  private AlpacaTradeUpdatesStream perTenantStream(BrokerCredentialSource creds) {
    return new AlpacaTradeUpdatesStream(
        new FillListenerProperties(
            true, "ws://localhost:" + port + "/stream", true, 100L, 1_000L, 32),
        new AlpacaProperties("http://unused", "test-key", "test-secret"),
        creds,
        dispatcher,
        metrics,
        new ObjectMapper().registerModule(new JavaTimeModule()));
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

  /** Always throws on resolve; used on the OFF path where the source must never be touched. */
  private static class ThrowingCredentialSource implements BrokerCredentialSource {
    @Override
    public BrokerCredentials resolve(String tenantId, String provider) {
      throw new IllegalStateException(
          "credential source must not be used on the single-socket path");
    }
  }

  /**
   * In-memory per-tenant credential source. The roster defaults to the map keys; {@link #enumerate}
   * overrides it to include tenants that have NO entry (so resolve throws for them — the
   * skip-on-failure case).
   */
  private static class MapCredentialSource implements BrokerCredentialSource {
    private final Map<String, BrokerCredentials> byTenant;
    private Set<String> roster;

    MapCredentialSource(Map<String, BrokerCredentials> byTenant) {
      this.byTenant = byTenant;
      this.roster = byTenant.keySet();
    }

    void enumerate(String... tenants) {
      this.roster = Set.of(tenants);
    }

    @Override
    public BrokerCredentials resolve(String tenantId, String provider) {
      BrokerCredentials c = byTenant.get(tenantId);
      if (c == null) {
        throw BrokerCredentialSource.unavailable("no creds for tenant=" + tenantId);
      }
      return c;
    }

    @Override
    public Set<String> liveTenants(String provider) {
      return roster;
    }
  }
}
