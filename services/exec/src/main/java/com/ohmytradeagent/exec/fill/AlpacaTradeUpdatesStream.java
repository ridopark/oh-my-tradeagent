package com.ohmytradeagent.exec.fill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.exec.broker.alpaca.AlpacaProperties;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Long-running Alpaca trade-updates WebSocket listener. Connects to the configured stream URL,
 * sends the {@code authenticate} + {@code listen} handshake frames, parses incoming JSON, filters
 * to {@code fill} / {@code partial_fill}, dedupes on {@code (broker_order_id, filled_qty)}, and
 * hands each surviving event to a {@link FillDispatcher}.
 *
 * <p><b>Single-pod constraint.</b> This component is NOT leader-elected. The exec service must
 * deploy with {@code replicas: 1}; multi-pod scaling would multiply dispatch cost by N.
 *
 * <p>Lifecycle: starts in a daemon thread on {@link ApplicationReadyEvent} so Temporal worker
 * registration completes first; stops on bean destruction.
 */
// Gate on BOTH flags so an operator who flips fill-listener on with broker.impl=stub gets a clean
// "bean disabled, condition not met" startup message instead of a cryptic
// NoSuchBeanDefinitionException
// on AlpacaProperties (which AlpacaConfig only registers for an alpaca-* broker.impl).
@Component
@ConditionalOnExpression(
    "'${broker.impl:}'.startsWith('alpaca-') and ${exec.fill-listener.enabled:false}")
@EnableConfigurationProperties(FillListenerProperties.class)
public class AlpacaTradeUpdatesStream {

  private static final Logger log = LoggerFactory.getLogger(AlpacaTradeUpdatesStream.class);
  private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(2);

  /**
   * Upper bound on a single accumulated text frame. Alpaca fills are sub-kilobyte; a runaway
   * continuation frame past this size aborts the socket and lets the reconnect loop recover rather
   * than letting the listener OOM.
   */
  private static final int MAX_FRAME_BYTES = 1 << 20;

  private final FillListenerProperties props;
  private final AlpacaProperties alpacaProps;
  private final FillDispatcher dispatcher;
  private final FillListenerMetrics metrics;
  private final ObjectMapper mapper;
  private final HttpClient http;

  private final Map<String, Boolean> dedup;
  private final AtomicReference<WebSocket> currentSocket = new AtomicReference<>();

  private volatile boolean stopped;
  private Thread runner;

  public AlpacaTradeUpdatesStream(
      FillListenerProperties props,
      AlpacaProperties alpacaProps,
      FillDispatcher dispatcher,
      FillListenerMetrics metrics,
      ObjectMapper mapper) {
    this.props = props;
    this.alpacaProps = alpacaProps;
    this.dispatcher = dispatcher;
    this.metrics = metrics;
    this.mapper = mapper;
    this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    this.dedup =
        new LinkedHashMap<>(props.dedupCacheSize(), 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > props.dedupCacheSize();
          }
        };
  }

  @EventListener(ApplicationReadyEvent.class)
  public void start() {
    runner = new Thread(this::runForever, "fill-listener-ws");
    runner.setDaemon(true);
    runner.start();
    log.info("fill-listener started ws_url={}", props.wsUrl());
  }

  @PreDestroy
  public void stop() {
    stopped = true;
    WebSocket ws = currentSocket.getAndSet(null);
    if (ws != null) {
      try {
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown")
            .toCompletableFuture()
            .get(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      } catch (ExecutionException | TimeoutException | RuntimeException e) {
        log.warn("fill-listener close failed: {}", e.toString());
      }
    }
    if (runner != null) {
      runner.interrupt();
      try {
        runner.join(SHUTDOWN_TIMEOUT.toMillis());
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
    }
  }

  void runForever() {
    long backoff = props.reconnectBaseMs();
    boolean firstAttempt = true;
    while (!stopped) {
      try {
        if (!firstAttempt) {
          metrics.recordReconnect();
        }
        firstAttempt = false;
        connectAndRun();
        // Clean return = the WS connected, served traffic, then closed normally.
        // Reset backoff so the NEXT reconnect doesn't pay for the cap from a
        // long-lived prior session.
        backoff = props.reconnectBaseMs();
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException e) {
        log.warn("fill-listener connect/run failed: {}", e.toString());
        backoff = Math.min(backoff * 2L, props.reconnectCapMs());
      }
      if (stopped) {
        return;
      }
      try {
        Thread.sleep(backoff);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  void connectAndRun() throws InterruptedException {
    CountDownLatch closed = new CountDownLatch(1);
    Listener listener = new Listener(closed);
    WebSocket ws;
    try {
      ws =
          http.newWebSocketBuilder()
              .buildAsync(URI.create(props.wsUrl()), listener)
              .get(HANDSHAKE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (ExecutionException | TimeoutException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      throw new RuntimeException("ws connect failed: " + cause, cause);
    }
    currentSocket.set(ws);
    sendAuth(ws);
    sendListen(ws);
    closed.await();
    currentSocket.compareAndSet(ws, null);
  }

  private void sendAuth(WebSocket ws) throws InterruptedException {
    Map<String, Object> frame =
        Map.of(
            "action",
            "authenticate",
            "data",
            Map.of(
                "key_id",
                nullToEmpty(alpacaProps.apiKeyId()),
                "secret_key",
                nullToEmpty(alpacaProps.apiSecretKey())));
    sendTextWithTimeout(ws, serialize(frame));
  }

  private void sendListen(WebSocket ws) throws InterruptedException {
    Map<String, Object> frame =
        Map.of("action", "listen", "data", Map.of("streams", java.util.List.of("trade_updates")));
    sendTextWithTimeout(ws, serialize(frame));
  }

  private void sendTextWithTimeout(WebSocket ws, String frame) throws InterruptedException {
    try {
      ws.sendText(frame, true)
          .toCompletableFuture()
          .get(HANDSHAKE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (ExecutionException | TimeoutException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      throw new RuntimeException("ws sendText failed: " + cause, cause);
    }
  }

  private String serialize(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("ws frame serialization failed", e);
    }
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private void handleFrame(String frame) {
    JsonNode root;
    try {
      root = mapper.readTree(frame);
    } catch (Exception e) {
      log.warn("fill-listener parse failed: {}", e.toString());
      return;
    }
    JsonNode streamNode = root.get("stream");
    if (streamNode == null || !"trade_updates".equals(streamNode.asText())) {
      return;
    }
    JsonNode data = root.get("data");
    if (data == null) {
      return;
    }
    String event = data.path("event").asText("");
    metrics.markEvent();
    metrics.recordReceived(event);
    if (!"fill".equals(event) && !"partial_fill".equals(event)) {
      return;
    }
    JsonNode order = data.get("order");
    if (order == null) {
      return;
    }
    String brokerOrderId = order.path("id").asText(null);
    String clientOrderId = order.path("client_order_id").asText(null);
    long filledQty = order.path("filled_qty").asLong(0L);
    BigDecimal avgFillPrice =
        order.hasNonNull("filled_avg_price")
            ? new BigDecimal(order.get("filled_avg_price").asText())
            : null;
    OffsetDateTime filledAt =
        order.hasNonNull("filled_at")
            ? OffsetDateTime.parse(order.get("filled_at").asText())
            : null;
    if (brokerOrderId == null || avgFillPrice == null || filledAt == null) {
      log.warn(
          "fill-listener missing field broker_order_id={} avg={} at={}",
          brokerOrderId,
          avgFillPrice,
          filledAt);
      return;
    }
    String dedupKey = brokerOrderId + "|" + filledQty;
    if (dedup.putIfAbsent(dedupKey, Boolean.TRUE) != null) {
      metrics.recordDroppedDedup();
      return;
    }
    BrokerFillEvent fill =
        new BrokerFillEvent(
            brokerOrderId,
            clientOrderId,
            filledQty,
            avgFillPrice,
            filledAt,
            BrokerFillEvent.Source.WS);
    try {
      dispatcher.dispatch(fill);
    } catch (RuntimeException e) {
      log.error("fill-listener dispatch failed broker_order_id={}", brokerOrderId, e);
      // Stream stays alive — a dispatcher fault must not blind the listener.
    }
    // events_dispatched_total is bumped inside FillDispatcherImpl after the
    // signal succeeds, so WS and poll paths share one accounting point.
  }

  private class Listener implements WebSocket.Listener {
    private final CountDownLatch closed;
    private final StringBuilder partialFrame = new StringBuilder();

    Listener(CountDownLatch closed) {
      this.closed = closed;
    }

    @Override
    public void onOpen(WebSocket webSocket) {
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      if (partialFrame.length() + data.length() > MAX_FRAME_BYTES) {
        log.warn(
            "fill-listener frame exceeds {} bytes; aborting socket to recover via reconnect",
            MAX_FRAME_BYTES);
        partialFrame.setLength(0);
        webSocket.abort();
        closed.countDown();
        return null;
      }
      partialFrame.append(data);
      if (last) {
        String frame = partialFrame.toString();
        partialFrame.setLength(0);
        handleFrame(frame);
      }
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      log.info("fill-listener ws closed code={} reason={}", statusCode, reason);
      closed.countDown();
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      log.warn("fill-listener ws error: {}", error.toString());
      closed.countDown();
    }
  }
}
