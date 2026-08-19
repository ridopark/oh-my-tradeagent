package com.ohmytradeagent.exec.fill;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.slf4j.LoggerFactory;

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

  // ---------------------------------------------------------------------------------------------
  // #693 — Alpaca delivers trade_updates as BINARY frames carrying JSON. `onBinary` was never
  // implemented, so the JDK default silently request(1)'d and discarded every frame: no fill has
  // ever reached the dispatcher over the WS, and the 30s poller behind a 60s grace window quietly
  // discovered all of them (measured: BUY observe lag p50 69.2s, SELL 30.2s).
  // ---------------------------------------------------------------------------------------------

  @Test
  void binaryFrameIsParsedAndDispatched() throws Exception {
    // The incident reproduction: byte-for-byte the payload Alpaca sends, delivered on the BINARY
    // channel instead of TEXT. Fails before onBinary exists.
    stream.start();
    awaitHandshake();

    server.broadcastBinary(
        "{\"stream\":\"trade_updates\",\"data\":{\"event\":\"fill\","
            + "\"order\":{\"id\":\"brk-bin\",\"client_order_id\":\"ck-bin\","
            + "\"filled_qty\":\"7\",\"filled_avg_price\":\"1.23\","
            + "\"filled_at\":\"2026-08-16T14:30:00Z\"}}}");

    BrokerFillEvent fill = dispatcher.events.poll(AWAIT_MS, TimeUnit.MILLISECONDS);
    assertThat(fill).isNotNull();
    assertThat(fill.brokerOrderId()).isEqualTo("brk-bin");
    assertThat(fill.clientOrderId()).isEqualTo("ck-bin");
    assertThat(fill.filledQty()).isEqualTo(7L);
    assertThat(fill.avgFillPrice()).isEqualByComparingTo(new BigDecimal("1.23"));
    assertThat(fill.filledAt()).isEqualTo(OffsetDateTime.parse("2026-08-16T14:30:00Z"));
    assertThat(fill.source()).isEqualTo(BrokerFillEvent.Source.WS);
    assertThat(registry.counter("fill_listener.events_received", "event", "fill").count())
        .isEqualTo(1.0);
  }

  /**
   * #715: a frame that ARRIVES but never completes is invisible to every pre-existing signal.
   *
   * <p>`handleFrame` is only reached on the final fragment, and every counter in {@link
   * FillListenerMetrics} lives behind it — so `events_received`, `frames_without_stream`, the
   * unhandled-stream warn and `last_event_age_seconds` all read exactly as they do when the broker
   * sends nothing at all. `reconnects` stays zero too: the oversize abort is at 1MB, and a
   * session's worth of fragments is kilobytes. The two states are indistinguishable, and one of
   * them is OUR fault.
   *
   * <p>This test pins the instrument that tells them apart. It is the whole reason the gauge
   * exists: bytes sitting in the accumulator while nothing is dispatched means the frames reached
   * us and we failed to complete them.
   */
  @Test
  void aFrameThatNeverCompletesIsVisibleInTheAccumulatorGauge() throws Exception {
    stream.start();
    awaitHandshake();

    byte[] chunk =
        "{\"stream\":\"trade_updates\",\"data\":{\"event\":\"fill\",\"order\":{\"id\":\"never-ends\""
            .getBytes(StandardCharsets.UTF_8);
    server.sendNonFinalBinary(chunk);

    // The accumulator must show the bytes are HERE.
    long deadline = System.currentTimeMillis() + AWAIT_MS;
    double buffered = 0.0;
    while (System.currentTimeMillis() < deadline) {
      io.micrometer.core.instrument.Gauge g =
          registry.find("fill_listener.ws_partial_bytes").gauge();
      if (g != null && g.value() > 0.0) {
        buffered = g.value();
        break;
      }
      Thread.sleep(25L);
    }
    assertThat(buffered).isGreaterThan(0.0);

    // ...and the callback boundary was crossed, on a NON-final fragment.
    assertThat(registry.counter("fill_listener.ws_callbacks", "channel", "binary").count())
        .isGreaterThanOrEqualTo(1.0);
    assertThat(registry.counter("fill_listener.ws_fragments", "channel", "binary").count())
        .isGreaterThanOrEqualTo(1.0);

    // Meanwhile EVERY pre-existing signal is silent — which is exactly the problem.
    assertThat(dispatcher.events.poll(200L, TimeUnit.MILLISECONDS)).isNull();
    assertThat(registry.counter("fill_listener.events_received", "event", "fill").count()).isZero();
    assertThat(registry.counter("fill_listener.frames_without_stream").count()).isZero();
    assertThat(registry.counter("fill_listener.reconnects").count()).isZero();
  }

  /**
   * #715 review finding: the instrumentation must not be able to mute the socket it instruments.
   *
   * <p>Both new calls run from onText/onBinary BEFORE {@code webSocket.request(1)}, the same hazard
   * the {@link AlpacaTradeUpdatesStream} comment on {@code handleFrame} spells out — an escaping
   * exception skips the request and the connection is never fed another frame. And it is not
   * hypothetical: {@code recordPartialBytes} registers a Micrometer gauge on the first call per
   * tenant, and meter registration can throw.
   *
   * <p>Diagnostics that silence the very socket they exist to diagnose would be the worst possible
   * outcome of this change, so a hostile metrics implementation must be survivable.
   */
  @Test
  void instrumentationThatThrowsDoesNotMuteTheSocket() throws Exception {
    FillListenerMetrics hostile = org.mockito.Mockito.mock(FillListenerMetrics.class);
    org.mockito.Mockito.doThrow(new IllegalStateException("meter registration failed"))
        .when(hostile)
        .recordPartialBytes(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
    org.mockito.Mockito.doThrow(new IllegalStateException("counter blew up"))
        .when(hostile)
        .recordWsCallback(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean());

    stream.stop();
    FillListenerProperties props =
        new FillListenerProperties(
            true, "ws://localhost:" + port + "/stream", false, 100L, 1_000L, 32);
    stream =
        new AlpacaTradeUpdatesStream(
            props,
            new AlpacaProperties("http://unused", "test-key", "test-secret"),
            new ThrowingCredentialSource(),
            dispatcher,
            hostile,
            new ObjectMapper().registerModule(new JavaTimeModule()));
    stream.start();
    awaitHandshake();

    server.broadcastBinary(
        "{\"stream\":\"trade_updates\",\"data\":{\"event\":\"fill\","
            + "\"order\":{\"id\":\"brk-hostile\",\"client_order_id\":\"ck-hostile\","
            + "\"filled_qty\":\"3\",\"filled_avg_price\":\"2.50\","
            + "\"filled_at\":\"2026-08-16T14:30:00Z\"}}}");

    BrokerFillEvent fill = dispatcher.events.poll(AWAIT_MS, TimeUnit.MILLISECONDS);
    assertThat(fill).isNotNull();
    assertThat(fill.brokerOrderId()).isEqualTo("brk-hostile");
  }

  /** The completing case must clear the accumulator, or the gauge would read as a false alarm. */
  @Test
  void aCompletedFrameClearsTheAccumulatorGauge() throws Exception {
    stream.start();
    awaitHandshake();

    server.broadcastBinary(
        "{\"stream\":\"trade_updates\",\"data\":{\"event\":\"fill\","
            + "\"order\":{\"id\":\"brk-clear\",\"client_order_id\":\"ck-clear\","
            + "\"filled_qty\":\"1\",\"filled_avg_price\":\"1.00\","
            + "\"filled_at\":\"2026-08-16T14:30:00Z\"}}}");

    assertThat(dispatcher.events.poll(AWAIT_MS, TimeUnit.MILLISECONDS)).isNotNull();
    io.micrometer.core.instrument.Gauge g = registry.find("fill_listener.ws_partial_bytes").gauge();
    assertThat(g).isNotNull();
    assertThat(g.value()).isZero();
  }

  @Test
  void binaryFrameSplitAcrossFragmentsIsReassembled() throws Exception {
    stream.start();
    awaitHandshake();

    String payload =
        "{\"stream\":\"trade_updates\",\"data\":{\"event\":\"fill\","
            + "\"order\":{\"id\":\"brk-binmf\",\"client_order_id\":\"ck-binmf\","
            + "\"filled_qty\":\"2\",\"filled_avg_price\":\"3.30\","
            + "\"filled_at\":\"2026-08-16T15:00:00Z\"}}}";
    byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
    int mid = bytes.length / 2;
    server.sendFragmentedBinary(
        java.util.Arrays.copyOfRange(bytes, 0, mid),
        java.util.Arrays.copyOfRange(bytes, mid, bytes.length));

    BrokerFillEvent fill = dispatcher.events.poll(AWAIT_MS, TimeUnit.MILLISECONDS);
    assertThat(fill).isNotNull();
    assertThat(fill.brokerOrderId()).isEqualTo("brk-binmf");
    assertThat(fill.filledQty()).isEqualTo(2L);
  }

  @Test
  void binaryFrameSplitMidUtf8SequenceDoesNotCorrupt() throws Exception {
    // The reason the accumulator must hold BYTES, not chars. "é" is 2 bytes in UTF-8; splitting
    // between them and decoding each fragment independently yields two replacement characters,
    // so the client_order_id no longer round-trips. A byte accumulator decoded once on `last`
    // reassembles the character intact.
    stream.start();
    awaitHandshake();

    String clientOrderId = "ck-café-bin";
    String payload =
        "{\"stream\":\"trade_updates\",\"data\":{\"event\":\"fill\","
            + "\"order\":{\"id\":\"brk-utf8\",\"client_order_id\":\""
            + clientOrderId
            + "\",\"filled_qty\":\"1\",\"filled_avg_price\":\"0.55\","
            + "\"filled_at\":\"2026-08-16T15:30:00Z\"}}}";
    byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);

    // Split exactly between the two bytes of the 'é' continuation sequence.
    int accentStart = payload.indexOf("café") + 3;
    int splitAt = clientOrderIdByteOffset(payload, accentStart) + 1;
    assertThat(splitAt).isBetween(1, bytes.length - 1);

    server.sendFragmentedBinary(
        java.util.Arrays.copyOfRange(bytes, 0, splitAt),
        java.util.Arrays.copyOfRange(bytes, splitAt, bytes.length));

    BrokerFillEvent fill = dispatcher.events.poll(AWAIT_MS, TimeUnit.MILLISECONDS);
    assertThat(fill).isNotNull();
    assertThat(fill.brokerOrderId()).isEqualTo("brk-utf8");
    assertThat(fill.clientOrderId())
        .as("multi-byte character split across fragments must survive reassembly")
        .isEqualTo(clientOrderId);
  }

  @Test
  void binaryAndTextFramesBothDispatchExactlyOnce() throws Exception {
    // Alpaca's channel choice is not contractual and paper/live may differ, so both are handled.
    // Guards the obvious way to get this wrong: routing binary through a second code path that
    // also re-handles text, double-dispatching every fill.
    stream.start();
    awaitHandshake();

    server.broadcastBinary(
        "{\"stream\":\"trade_updates\",\"data\":{\"event\":\"fill\","
            + "\"order\":{\"id\":\"brk-b\",\"client_order_id\":\"ck-b\","
            + "\"filled_qty\":\"1\",\"filled_avg_price\":\"1.00\","
            + "\"filled_at\":\"2026-08-16T16:00:00Z\"}}}");
    server.broadcast(
        "{\"stream\":\"trade_updates\",\"data\":{\"event\":\"fill\","
            + "\"order\":{\"id\":\"brk-t\",\"client_order_id\":\"ck-t\","
            + "\"filled_qty\":\"1\",\"filled_avg_price\":\"1.00\","
            + "\"filled_at\":\"2026-08-16T16:00:01Z\"}}}");

    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      BrokerFillEvent fill = dispatcher.events.poll(AWAIT_MS, TimeUnit.MILLISECONDS);
      assertThat(fill).isNotNull();
      ids.add(fill.brokerOrderId());
    }
    assertThat(ids).containsExactlyInAnyOrder("brk-b", "brk-t");
    assertThat(dispatcher.events.poll(500, TimeUnit.MILLISECONDS))
        .as("neither channel may dispatch twice")
        .isNull();
  }

  @Test
  void binaryFrameExceedingMaxBytesAbortsSocketAndReconnects() throws Exception {
    // Parity with the onText guard at MAX_FRAME_BYTES: a runaway continuation must abort the
    // socket and recover via the reconnect loop rather than accumulating until OOM.
    stream.start();
    awaitHandshake();

    byte[] oversized = new byte[(1 << 20) + 1024];
    java.util.Arrays.fill(oversized, (byte) 'x');
    server.broadcastBinaryBytes(oversized);

    assertThat(dispatcher.events.poll(500, TimeUnit.MILLISECONDS)).isNull();
    // Recovery is observable as a fresh handshake on the reconnect.
    String auth = server.frames.poll(AWAIT_MS, TimeUnit.MILLISECONDS);
    String listen = server.frames.poll(AWAIT_MS, TimeUnit.MILLISECONDS);
    assertThat(auth).isNotNull();
    assertThat(auth).contains("\"action\":\"authenticate\"");
    assertThat(listen).isNotNull();

    // And the recovered socket still works.
    server.broadcastBinary(
        "{\"stream\":\"trade_updates\",\"data\":{\"event\":\"fill\","
            + "\"order\":{\"id\":\"brk-recov\",\"client_order_id\":\"ck-recov\","
            + "\"filled_qty\":\"1\",\"filled_avg_price\":\"0.75\","
            + "\"filled_at\":\"2026-08-16T17:00:00Z\"}}}");
    BrokerFillEvent fill = dispatcher.events.poll(AWAIT_MS, TimeUnit.MILLISECONDS);
    assertThat(fill).isNotNull();
    assertThat(fill.brokerOrderId()).isEqualTo("brk-recov");
  }

  // Deliberately NOT tested: "the byte accumulator is reset on the oversize path." Mutation-checked
  // — deleting the reset leaves every test green, because abort() discards the Listener and the
  // reconnect builds a new one with a fresh accumulator. The reset is unobservable by construction
  // (the same is true of the onText path's setLength(0)); a test asserting it would pass no matter
  // what the code did. The recovery that IS observable is covered by the abort test above.

  @Test
  void authorizedHandshakeIsLoggedAtInfo() throws Exception {
    ListAppender<ILoggingEvent> logs = attachLogCapture();
    try {
      stream.start();
      awaitHandshake();

      server.broadcastBinary(
          "{\"stream\":\"authorization\",\"data\":"
              + "{\"status\":\"authorized\",\"action\":\"authenticate\"}}");

      ILoggingEvent event = awaitLog(logs, "authorization reply");
      assertThat(event).isNotNull();
      assertThat(event.getLevel()).isEqualTo(Level.INFO);
      assertThat(event.getFormattedMessage()).contains("status=authorized");
    } finally {
      detachLogCapture(logs);
    }
  }

  @Test
  void unauthorizedHandshakeIsLoggedAtWarn() throws Exception {
    // #693's own failure mode, applied to the fix: an unauthorized socket stays OPEN and simply
    // honors no subscriptions, so it is indistinguishable from a healthy quiet one. Logging that
    // at INFO — the same level as success — would recreate exactly the silence this PR removes.
    ListAppender<ILoggingEvent> logs = attachLogCapture();
    try {
      stream.start();
      awaitHandshake();

      server.broadcastBinary(
          "{\"stream\":\"authorization\",\"data\":"
              + "{\"status\":\"unauthorized\",\"action\":\"authenticate\"}}");

      ILoggingEvent event = awaitLog(logs, "authorization reply");
      assertThat(event).isNotNull();
      assertThat(event.getLevel())
          .as("a refused handshake must not be indistinguishable from a successful one")
          .isEqualTo(Level.WARN);
      assertThat(event.getFormattedMessage()).contains("status=unauthorized");
    } finally {
      detachLogCapture(logs);
    }
  }

  @Test
  void unrecognizedAuthorizationShapeIsLoggedAtWarn() throws Exception {
    // Fail loud on an unknown reply shape rather than assume success. Alpaca's error payload
    // carries a message rather than a status, so "no status field" must not read as authorized.
    ListAppender<ILoggingEvent> logs = attachLogCapture();
    try {
      stream.start();
      awaitHandshake();

      server.broadcastBinary(
          "{\"stream\":\"authorization\",\"data\":{\"message\":\"access key verification failed\"}}");

      ILoggingEvent event = awaitLog(logs, "authorization reply");
      assertThat(event).isNotNull();
      assertThat(event.getLevel()).isEqualTo(Level.WARN);
      assertThat(event.getFormattedMessage()).contains("access key verification failed");
    } finally {
      detachLogCapture(logs);
    }
  }

  @Test
  void preAuthListenRejectionIsLoggedAtWarn() throws Exception {
    // Pins the discriminator the whole #715 investigation turns on. Alpaca answers a `listen` sent
    // before authorization completes on the AUTHORIZATION stream, not the listening one:
    // "In the case that the socket connection is not authorized yet, a new message under the
    // authorization stream is issued in response to the listen request."
    //
    // The existing branch already handles this shape — the value here is that no test pinned it.
    // The other two authorization tests only cover action=authenticate, so nothing stopped a future
    // refactor from collapsing the action field out of the message, and `action` is the ONLY thing
    // separating "listen sent too early" (a race, fixable by ordering the handshake) from "bad
    // credentials" (action=authenticate). Absence of this line on the cluster is what refuted the
    // race hypothesis; a test that keeps the line honest is what makes that reasoning repeatable.
    ListAppender<ILoggingEvent> logs = attachLogCapture();
    try {
      stream.start();
      awaitHandshake();

      server.broadcastBinary(
          "{\"stream\":\"authorization\",\"data\":"
              + "{\"status\":\"unauthorized\",\"action\":\"listen\"}}");

      ILoggingEvent event = awaitLog(logs, "authorization reply");
      assertThat(event).isNotNull();
      assertThat(event.getLevel()).isEqualTo(Level.WARN);
      assertThat(event.getFormattedMessage())
          .as("action= is the only thing separating a pre-auth listen from bad credentials")
          .contains("action=listen");
    } finally {
      detachLogCapture(logs);
    }
  }

  @Test
  void listeningConfirmationIsLoggedAtInfo() throws Exception {
    // #715: POSITIVE evidence that the subscription exists. Alpaca acks a successful `listen` with
    // {"stream":"listening","data":{"streams":["trade_updates"]}}, which this class used to drop on
    // the floor along with everything else that wasn't authorization/trade_updates. Without it, a
    // socket that authenticated but never subscribed is indistinguishable from a healthy quiet one
    // — the SAME blind spot #693 fixed for the auth frame, left in place one step downstream.
    ListAppender<ILoggingEvent> logs = attachLogCapture();
    try {
      stream.start();
      awaitHandshake();

      server.broadcastBinary(
          "{\"stream\":\"listening\",\"data\":{\"streams\":[\"trade_updates\"]}}");

      ILoggingEvent event = awaitLog(logs, "subscription confirmed");
      assertThat(event).isNotNull();
      assertThat(event.getLevel()).isEqualTo(Level.INFO);
      assertThat(event.getFormattedMessage()).contains("trade_updates");
      assertThat(registry.counter("fill_listener.subscription_confirmed").count()).isEqualTo(1.0);
    } finally {
      detachLogCapture(logs);
    }
  }

  @Test
  void listeningWithoutTradeUpdatesIsLoggedAtWarn() throws Exception {
    // Alpaca echoes the EFFECTIVE subscription: "if any of the requested streams are not available,
    // they will not appear in the streams list in the acknowledgement". So an ack that omits
    // trade_updates is a FAILED subscription wearing a success-shaped frame. Logging it at INFO
    // alongside the real thing would rebuild the very blind spot this test exists to close.
    //
    // NOTE this is NOT the pre-auth-race signature. Alpaca answers a `listen` sent before
    // authorization on the AUTHORIZATION stream (status=unauthorized, action=listen), which the
    // existing branch already logs — see unauthorizedHandshakeIsLoggedAtWarn. Two distinct
    // failures; they must stay distinguishable in the log.
    ListAppender<ILoggingEvent> logs = attachLogCapture();
    try {
      stream.start();
      awaitHandshake();

      server.broadcastBinary("{\"stream\":\"listening\",\"data\":{\"streams\":[]}}");

      ILoggingEvent event = awaitLog(logs, "subscription ack");
      assertThat(event).isNotNull();
      assertThat(event.getLevel())
          .as("an ack that does not name trade_updates is a failed subscription, not a success")
          .isEqualTo(Level.WARN);
      assertThat(registry.counter("fill_listener.subscription_confirmed").count())
          .as("a failed subscription must not bump the confirmation counter")
          .isEqualTo(0.0);
    } finally {
      detachLogCapture(logs);
    }
  }

  @Test
  void unrecognizedStreamIsLoggedAtWarn() throws Exception {
    // The catch-all. Every frame this class does not understand used to hit a bare `return`, which
    // is how a server-side error reply could be delivered, parsed, and discarded without trace.
    ListAppender<ILoggingEvent> logs = attachLogCapture();
    try {
      stream.start();
      awaitHandshake();

      server.broadcastBinary(
          "{\"stream\":\"error\",\"data\":{\"message\":\"something we do not model\"}}");

      ILoggingEvent event = awaitLog(logs, "unhandled stream");
      assertThat(event).isNotNull();
      assertThat(event.getLevel()).isEqualTo(Level.WARN);
      assertThat(event.getFormattedMessage()).contains("error");
    } finally {
      detachLogCapture(logs);
    }
  }

  @Test
  void frameWithoutStreamFieldIsLoggedAtWarnAndCounted() throws Exception {
    // The last silent drop in handleFrame. #720 added a WARN for unmodelled `stream` VALUES, but it
    // sits AFTER the null check, so a frame carrying no top-level `stream` field at all still
    // vanished without log or metric. That is indistinguishable from a healthy quiet socket — the
    // exact failure mode #693/#694/#720 each closed one layer of — and it is the leading remaining
    // explanation for #715, where the socket is authorized AND subscribed AND silent.
    ListAppender<ILoggingEvent> logs = attachLogCapture();
    try {
      stream.start();
      awaitHandshake();

      server.broadcastBinary("{\"data\":{\"event\":\"fill\"},\"T\":\"t\"}");

      ILoggingEvent event = awaitLog(logs, "no top-level `stream` field");
      assertThat(event).isNotNull();
      assertThat(event.getLevel()).isEqualTo(Level.WARN);
      assertThat(event.getFormattedMessage()).contains("data").contains("T");
      assertThat(registry.counter("fill_listener.frames_without_stream").count()).isEqualTo(1.0);
    } finally {
      detachLogCapture(logs);
    }
  }

  @Test
  void frameWithoutStreamFieldLogsShapeNeverValues() throws Exception {
    // A frame we do not model is, by definition, one whose contents we cannot reason about — and
    // the handshake this listener sends contains the broker key and secret. Dumping an unknown
    // inbound frame verbatim would risk writing a credential into the pod log, permanently, to
    // diagnose a logging gap. Field NAMES identify the envelope, which is the entire diagnostic
    // need here; values add nothing and carry the whole risk.
    ListAppender<ILoggingEvent> logs = attachLogCapture();
    try {
      stream.start();
      awaitHandshake();

      server.broadcastBinary(
          "{\"secret_key\":\"SUPERSECRET-must-never-be-logged\",\"key_id\":\"AKIDNOPE\"}");

      ILoggingEvent event = awaitLog(logs, "no top-level `stream` field");
      assertThat(event).isNotNull();
      assertThat(event.getFormattedMessage())
          .as("field names are the diagnostic")
          .contains("secret_key")
          .contains("key_id");
      assertThat(event.getFormattedMessage())
          .as("values must NEVER reach the log — this frame is unmodelled by definition")
          .doesNotContain("SUPERSECRET-must-never-be-logged")
          .doesNotContain("AKIDNOPE");
    } finally {
      detachLogCapture(logs);
    }
  }

  @Test
  void arrayEnvelopeWithoutStreamFieldReportsItsShape() throws Exception {
    // The concrete shape worth catching: Alpaca's v2 streams deliver BATCHED ARRAYS
    // ([{"T":"t",...}]). ArrayNode.get("stream") returns null, so such a frame lands here. Naming
    // the element count and the first element's fields is what would let an operator recognise a
    // schema change rather than merely observe silence.
    ListAppender<ILoggingEvent> logs = attachLogCapture();
    try {
      stream.start();
      awaitHandshake();

      server.broadcastBinary("[{\"T\":\"t\",\"order\":{}},{\"T\":\"t\"}]");

      ILoggingEvent event = awaitLog(logs, "no top-level `stream` field");
      assertThat(event).isNotNull();
      assertThat(event.getLevel()).isEqualTo(Level.WARN);
      assertThat(event.getFormattedMessage()).contains("ARRAY").contains("2").contains("T");
    } finally {
      detachLogCapture(logs);
    }
  }

  private static ListAppender<ILoggingEvent> attachLogCapture() {
    Logger streamLogger = (Logger) LoggerFactory.getLogger(AlpacaTradeUpdatesStream.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    streamLogger.addAppender(appender);
    streamLogger.setLevel(Level.TRACE);
    return appender;
  }

  private static void detachLogCapture(ListAppender<ILoggingEvent> appender) {
    ((Logger) LoggerFactory.getLogger(AlpacaTradeUpdatesStream.class)).detachAppender(appender);
  }

  /** Frames arrive on a WS thread, so the log lands asynchronously; poll rather than race it. */
  private static ILoggingEvent awaitLog(ListAppender<ILoggingEvent> logs, String needle)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + AWAIT_MS;
    while (System.currentTimeMillis() < deadline) {
      synchronized (logs) {
        for (ILoggingEvent e : new ArrayList<>(logs.list)) {
          if (e.getFormattedMessage().contains(needle)) {
            return e;
          }
        }
      }
      Thread.sleep(20L);
    }
    return null;
  }

  @Test
  void malformedBinaryFrameIsSwallowed() throws Exception {
    stream.start();
    awaitHandshake();

    server.broadcastBinary("not json at all {{{");
    server.broadcastBinary(
        "{\"stream\":\"trade_updates\",\"data\":{\"event\":\"fill\","
            + "\"order\":{\"id\":\"brk-binok\",\"client_order_id\":\"ck-binok\","
            + "\"filled_qty\":\"1\",\"filled_avg_price\":\"2.00\","
            + "\"filled_at\":\"2026-08-16T18:00:00Z\"}}}");

    BrokerFillEvent fill = dispatcher.events.poll(AWAIT_MS, TimeUnit.MILLISECONDS);
    assertThat(fill).isNotNull();
    assertThat(fill.brokerOrderId()).isEqualTo("brk-binok");
  }

  /** Byte offset of {@code charIndex} within {@code s} once encoded as UTF-8. */
  private static int clientOrderIdByteOffset(String s, int charIndex) {
    return s.substring(0, charIndex).getBytes(StandardCharsets.UTF_8).length;
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

  // ---------------------------------------------------------------------------------------------
  // Phase 3 — periodic re-enumeration (restart-free new-tenant socket)
  // ---------------------------------------------------------------------------------------------

  @Test
  void reenumerateStartsSocketForNewlyAppearedTenant() throws Exception {
    String url = "ws://localhost:" + port + "/stream";
    MapCredentialSource creds =
        new MapCredentialSource(
            Map.of("alice", new BrokerCredentials("alice-key", "alice-secret", "", url, "")));
    stream = perTenantStream(creds);
    stream.start();
    awaitHandshake(); // alice's initial auth + listen
    assertThat(stream.runnerCount()).isEqualTo(1);

    // bob appears in the roster AFTER startup.
    creds.put("bob", new BrokerCredentials("bob-key", "bob-secret", "", url, ""));
    stream.reenumerateOnce();

    // Exactly ONE new runner opens (for bob); alice's runner is untouched (not restarted).
    assertThat(stream.runnerCount()).isEqualTo(2);
    String f1 = server.frames.poll(AWAIT_MS, TimeUnit.MILLISECONDS);
    String f2 = server.frames.poll(AWAIT_MS, TimeUnit.MILLISECONDS);
    assertThat(f1).isNotNull();
    assertThat(f2).isNotNull();
    String bobAuth = f1.contains("\"authenticate\"") ? f1 : f2;
    assertThat(bobAuth).contains("\"key_id\":\"bob-key\"");
    // alice did NOT re-authenticate (no restart) → no further frames arrive.
    assertThat(server.frames.poll(500, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test
  void reenumerateNoChangeIsNoOp() throws Exception {
    String url = "ws://localhost:" + port + "/stream";
    MapCredentialSource creds =
        new MapCredentialSource(
            Map.of("alice", new BrokerCredentials("alice-key", "alice-secret", "", url, "")));
    stream = perTenantStream(creds);
    stream.start();
    awaitHandshake();
    assertThat(stream.runnerCount()).isEqualTo(1);

    // Roster unchanged ({alice}) → tick must not open a second runner or re-handshake alice.
    stream.reenumerateOnce();

    assertThat(stream.runnerCount()).isEqualTo(1);
    assertThat(server.frames.poll(500, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test
  void reenumerateIsIdempotentUnderConcurrentTicks() throws Exception {
    String url = "ws://localhost:" + port + "/stream";
    MapCredentialSource creds =
        new MapCredentialSource(
            Map.of("alice", new BrokerCredentials("alice-key", "alice-secret", "", url, "")));
    stream = perTenantStream(creds);
    stream.start();
    awaitHandshake();

    creds.put("bob", new BrokerCredentials("bob-key", "bob-secret", "", url, ""));

    // Two ticks racing for the same enumerated set — bob must get EXACTLY ONE runner.
    Thread t1 = new Thread(stream::reenumerateOnce);
    Thread t2 = new Thread(stream::reenumerateOnce);
    t1.start();
    t2.start();
    t1.join(AWAIT_MS);
    t2.join(AWAIT_MS);

    assertThat(stream.runnerCount()).isEqualTo(2);
    // Exactly one bob authenticate frame — no duplicate socket for an already-running tenant.
    List<String> frames = drainFrames(600);
    long bobAuths =
        frames.stream()
            .filter(f -> f.contains("\"authenticate\"") && f.contains("\"key_id\":\"bob-key\""))
            .count();
    assertThat(bobAuths).isEqualTo(1L);
  }

  @Test
  void reenumerateSurvivesEnumerationFaultThenRecovers() throws Exception {
    String url = "ws://localhost:" + port + "/stream";
    MapCredentialSource creds =
        new MapCredentialSource(
            Map.of("alice", new BrokerCredentials("alice-key", "alice-secret", "", url, "")));
    stream = perTenantStream(creds);
    stream.start();
    awaitHandshake();
    assertThat(stream.runnerCount()).isEqualTo(1);

    // A transient enumeration fault on a tick must be caught — no crash, runners unchanged.
    creds.failEnumeration(true);
    stream.reenumerateOnce();
    assertThat(stream.runnerCount()).isEqualTo(1);

    // A later good tick still picks up the new tenant.
    creds.failEnumeration(false);
    creds.put("bob", new BrokerCredentials("bob-key", "bob-secret", "", url, ""));
    stream.reenumerateOnce();
    assertThat(stream.runnerCount()).isEqualTo(2);
  }

  @Test
  void reenumerateSkipsNewBlankCredTenantWithoutAbortingLoop() throws Exception {
    String url = "ws://localhost:" + port + "/stream";
    MapCredentialSource creds =
        new MapCredentialSource(
            Map.of("alice", new BrokerCredentials("alice-key", "alice-secret", "", url, "")));
    stream = perTenantStream(creds);
    stream.start();
    awaitHandshake();

    // Two new tenants appear this tick: "blankguy" (blank creds → fail closed on its own runner)
    // and "bob" (good). The blank one must be skipped WITHOUT aborting the loop for bob.
    creds.put("blankguy", new BrokerCredentials("", "", "", url, ""));
    creds.put("bob", new BrokerCredentials("bob-key", "bob-secret", "", url, ""));
    stream.reenumerateOnce();

    // alice + bob have runners; blankguy does not.
    assertThat(stream.runnerCount()).isEqualTo(2);
    List<String> frames = drainFrames(600);
    long bobAuths =
        frames.stream()
            .filter(f -> f.contains("\"authenticate\"") && f.contains("\"key_id\":\"bob-key\""))
            .count();
    assertThat(bobAuths).isEqualTo(1L);
  }

  @Test
  void reenumerateRetriesTenantOnceItsCredsLand() throws Exception {
    // The liveness half of the docstring: a tenant skipped for blank creds is NOT recorded, so a
    // later tick — once its creds resolve — starts exactly one runner for it.
    String url = "ws://localhost:" + port + "/stream";
    MapCredentialSource creds =
        new MapCredentialSource(
            Map.of("alice", new BrokerCredentials("alice-key", "alice-secret", "", url, "")));
    stream = perTenantStream(creds);
    stream.start();
    awaitHandshake();
    assertThat(stream.runnerCount()).isEqualTo(1);

    // Tick 1: "late" appears with blank creds → skipped, NOT recorded (no runner opens).
    creds.put("late", new BrokerCredentials("", "", "", url, ""));
    stream.reenumerateOnce();
    assertThat(stream.runnerCount()).isEqualTo(1);

    // Its creds land; a later tick starts EXACTLY ONE runner for it.
    creds.put("late", new BrokerCredentials("late-key", "late-secret", "", url, ""));
    stream.reenumerateOnce();
    assertThat(stream.runnerCount()).isEqualTo(2);
    List<String> frames = drainFrames(600);
    long lateAuths =
        frames.stream()
            .filter(f -> f.contains("\"authenticate\"") && f.contains("\"key_id\":\"late-key\""))
            .count();
    assertThat(lateAuths).isEqualTo(1L);
  }

  @Test
  void reenumerateTickIsInertInSingleSocketMode() throws Exception {
    // Single-socket mode: the scheduled tick must NOT enumerate or add any runner.
    EnumTrackingSource cs = new EnumTrackingSource();
    stream =
        new AlpacaTradeUpdatesStream(
            new FillListenerProperties(
                true, "ws://localhost:" + port + "/stream", false, 100L, 1_000L, 32),
            new AlpacaProperties("http://unused", "test-key", "test-secret"),
            cs,
            dispatcher,
            metrics,
            new ObjectMapper().registerModule(new JavaTimeModule()));
    stream.start();
    awaitHandshake();
    assertThat(stream.runnerCount()).isEqualTo(1);

    stream.reenumerateTick(); // gated on perTenantEnabled() → inert

    assertThat(cs.liveTenantsCalled).isFalse();
    assertThat(stream.runnerCount()).isEqualTo(1);
  }

  private List<String> drainFrames(long quietMs) throws InterruptedException {
    List<String> out = new ArrayList<>();
    String f;
    while ((f = server.frames.poll(quietMs, TimeUnit.MILLISECONDS)) != null) {
      out.add(f);
    }
    return out;
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

    /** Sends {@code payload} as a single BINARY frame — the channel Alpaca actually uses. */
    void broadcastBinary(String payload) {
      broadcastBinaryBytes(payload.getBytes(StandardCharsets.UTF_8));
    }

    void broadcastBinaryBytes(byte[] payload) {
      List<WebSocket> snapshot;
      synchronized (clients) {
        snapshot = new ArrayList<>(clients);
      }
      for (WebSocket c : snapshot) {
        c.send(ByteBuffer.wrap(payload));
      }
    }

    /** BINARY counterpart of {@link #sendFragmented}, split at an arbitrary BYTE boundary. */
    /** #715: start a binary message and NEVER set FIN — the frame never completes. */
    void sendNonFinalBinary(byte[] chunk) {
      List<WebSocket> snapshot;
      synchronized (clients) {
        snapshot = new ArrayList<>(clients);
      }
      for (WebSocket c : snapshot) {
        c.sendFragmentedFrame(Opcode.BINARY, ByteBuffer.wrap(chunk), false);
      }
    }

    void sendFragmentedBinary(byte[] first, byte[] second) {
      List<WebSocket> snapshot;
      synchronized (clients) {
        snapshot = new ArrayList<>(clients);
      }
      for (WebSocket c : snapshot) {
        c.sendFragmentedFrame(Opcode.BINARY, ByteBuffer.wrap(first), false);
        c.sendFragmentedFrame(Opcode.BINARY, ByteBuffer.wrap(second), true);
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
   * In-memory per-tenant credential source. The roster derives from the (mutable) map keys unless
   * {@link #enumerate} sets an explicit roster (used to include tenants that have NO entry, so
   * resolve throws for them — the skip-on-failure case). {@link #put} adds a tenant at runtime (the
   * re-enumeration cases); {@link #failEnumeration} simulates a transient enumeration fault.
   */
  private static class MapCredentialSource implements BrokerCredentialSource {
    private final Map<String, BrokerCredentials> byTenant;
    private Set<String> roster; // explicit override; null → derive live from the map keys
    private volatile boolean failEnumeration;

    MapCredentialSource(Map<String, BrokerCredentials> byTenant) {
      this.byTenant = new java.util.HashMap<>(byTenant);
    }

    void enumerate(String... tenants) {
      this.roster = Set.of(tenants);
    }

    void put(String tenant, BrokerCredentials creds) {
      byTenant.put(tenant, creds);
    }

    void failEnumeration(boolean fail) {
      this.failEnumeration = fail;
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
      if (failEnumeration) {
        throw new RuntimeException("enumeration boom");
      }
      return roster != null ? roster : new java.util.HashSet<>(byTenant.keySet());
    }
  }

  /**
   * Single-socket-mode probe: records whether {@code liveTenants} is ever called and hard-fails if
   * it is, proving the scheduled re-enumeration tick is inert when {@code perTenantEnabled=false}.
   */
  private static class EnumTrackingSource implements BrokerCredentialSource {
    volatile boolean liveTenantsCalled;

    @Override
    public BrokerCredentials resolve(String tenantId, String provider) {
      throw new IllegalStateException(
          "credential source must not be used on the single-socket path");
    }

    @Override
    public Set<String> liveTenants(String provider) {
      liveTenantsCalled = true;
      throw new AssertionError("liveTenants must not be called in single-socket mode");
    }
  }
}
