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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Long-running Alpaca trade-updates WebSocket listener. Connects to the configured stream URL,
 * sends the {@code authenticate} + {@code listen} handshake frames, parses incoming JSON, filters
 * to {@code fill} / {@code partial_fill}, dedupes on {@code (broker_order_id, filled_qty)}, and
 * hands each surviving event to a {@link FillDispatcher} (Phase 1 ships a {@link
 * NoopFillDispatcher}; Phase 2 swaps in the real journal-lookup + workflow-signal impl).
 *
 * <p><b>Single-pod constraint.</b> This component is NOT leader-elected. The exec service must
 * deploy with {@code replicas: 1}; multi-pod scaling would multiply dispatch cost by N. Tracking
 * issue for leader-election is a follow-up of the fill-listener plan.
 *
 * <p>Lifecycle: starts in a daemon thread on {@link ApplicationReadyEvent} so Temporal worker
 * registration completes first; stops on bean destruction.
 */
@Component
@ConditionalOnProperty(name = "exec.fill-listener.enabled", havingValue = "true")
@EnableConfigurationProperties(FillListenerProperties.class)
public class AlpacaTradeUpdatesStream {

  private static final Logger log = LoggerFactory.getLogger(AlpacaTradeUpdatesStream.class);
  private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(5);

  private final FillListenerProperties props;
  private final AlpacaProperties alpacaProps;
  private final FillDispatcher dispatcher;
  private final FillListenerMetrics metrics;
  private final ObjectMapper mapper;

  private final Map<String, Boolean> dedup;
  private final StringBuilder partialFrame = new StringBuilder();
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
    this.dedup =
        Collections.synchronizedMap(
            new LinkedHashMap<>(props.dedupCacheSize(), 0.75f, true) {
              @Override
              protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > props.dedupCacheSize();
              }
            });
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
            .orTimeout(2, java.util.concurrent.TimeUnit.SECONDS);
      } catch (RuntimeException e) {
        log.warn("fill-listener close failed", e);
      }
    }
    if (runner != null) {
      runner.interrupt();
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
        // connectAndRun returned cleanly -> server closed the socket.
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException e) {
        log.warn("fill-listener connect/run failed: {}", e.toString());
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
      backoff = Math.min(backoff * 2L, props.reconnectCapMs());
    }
  }

  void connectAndRun() throws InterruptedException {
    CountDownLatch closed = new CountDownLatch(1);
    HttpClient http = HttpClient.newHttpClient();
    Listener listener = new Listener(closed);
    WebSocket ws;
    try {
      ws =
          http.newWebSocketBuilder()
              .buildAsync(URI.create(props.wsUrl()), listener)
              .get(HANDSHAKE_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
      throw new RuntimeException("ws connect failed: " + e.getMessage(), e);
    }
    currentSocket.set(ws);
    sendAuth(ws);
    sendListen(ws);
    closed.await();
    currentSocket.compareAndSet(ws, null);
  }

  private void sendAuth(WebSocket ws) {
    String frame =
        String.format(
            "{\"action\":\"authenticate\",\"data\":{\"key_id\":%s,\"secret_key\":%s}}",
            jsonString(alpacaProps.apiKeyId()), jsonString(alpacaProps.apiSecretKey()));
    ws.sendText(frame, true).join();
  }

  private void sendListen(WebSocket ws) {
    String frame = "{\"action\":\"listen\",\"data\":{\"streams\":[\"trade_updates\"]}}";
    ws.sendText(frame, true).join();
  }

  private static String jsonString(String value) {
    if (value == null) {
      return "\"\"";
    }
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
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
      return; // authorization / listening / other control frames
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
      metrics.recordDispatched();
    } catch (RuntimeException e) {
      log.error("fill-listener dispatch failed broker_order_id={}", brokerOrderId, e);
      // Do not close the socket on a dispatcher error — keep the stream alive.
    }
  }

  private class Listener implements WebSocket.Listener {
    private final CountDownLatch closed;

    Listener(CountDownLatch closed) {
      this.closed = closed;
    }

    @Override
    public void onOpen(WebSocket webSocket) {
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
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
