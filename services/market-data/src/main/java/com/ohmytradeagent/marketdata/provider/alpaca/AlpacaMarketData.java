package com.ohmytradeagent.marketdata.provider.alpaca;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.marketdata.provider.MarketDataProvider;
import com.ohmytradeagent.marketdata.provider.Quote;
import com.ohmytradeagent.marketdata.provider.Subscription;
import com.ohmytradeagent.marketdata.provider.Tick;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

/**
 * Alpaca {@link MarketDataProvider} adapter. Activated by {@code market-data.provider=alpaca}.
 *
 * <p>REST snapshot: {@code GET /v1beta1/options/snapshots?symbols=<occ>} via the shared {@link
 * RestClient}. JSON shape per Alpaca options market-data docs: {@code {"snapshots": {"<symbol>":
 * {"latestQuote": {"bp": 1.2, "ap": 1.3, "t": "..."} }}}}.
 *
 * <p>WebSocket stream: uses Java's {@code java.net.http.WebSocket} (no Netty/Spring-WebSocket dep).
 * On {@link #subscribePremium} the first subscriber per symbol triggers an upstream {@code
 * subscribe} message; the last subscriber's {@link Subscription#close()} sends {@code unsubscribe}
 * (best-effort). Reconnect uses exponential backoff capped at 30s and re-sends {@code subscribe}
 * for currently-active symbols — lossy semantics, no replay buffer.
 *
 * <p>The WS frame parser ({@link #dispatchWsMessage(String)}) is package-private so unit tests can
 * drive the fan-out path without standing up a real WS server.
 */
@Component
@ConditionalOnProperty(name = "market-data.provider", havingValue = "alpaca")
public class AlpacaMarketData implements MarketDataProvider {

  private static final Logger log = LoggerFactory.getLogger(AlpacaMarketData.class);
  private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

  private final RestClient rest;
  private final ObjectMapper mapper;
  private final AlpacaMarketDataProperties props;
  private final HttpClient httpClient;
  private final ScheduledExecutorService scheduler;

  /** Subscriber registry: symbol -> {listener, ...}. */
  private final ConcurrentHashMap<String, List<Consumer<Tick>>> bySymbol =
      new ConcurrentHashMap<>();

  private final Object wsLock = new Object();
  private volatile WebSocket ws;
  private final AtomicBoolean reconnectInFlight = new AtomicBoolean(false);
  private volatile Duration nextBackoff = Duration.ofSeconds(1);

  public AlpacaMarketData(
      RestClient alpacaMarketDataRestClient,
      ObjectMapper objectMapper,
      AlpacaMarketDataProperties props) {
    this(
        alpacaMarketDataRestClient,
        objectMapper,
        props,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
        defaultScheduler());
  }

  /** Visible for tests that want to inject a deterministic scheduler / fake HttpClient. */
  AlpacaMarketData(
      RestClient alpacaMarketDataRestClient,
      ObjectMapper objectMapper,
      AlpacaMarketDataProperties props,
      HttpClient httpClient,
      ScheduledExecutorService scheduler) {
    this.rest = alpacaMarketDataRestClient;
    this.mapper = objectMapper;
    this.props = props;
    this.httpClient = httpClient;
    this.scheduler = scheduler;
  }

  private static ScheduledExecutorService defaultScheduler() {
    ScheduledThreadPoolExecutor pool =
        new ScheduledThreadPoolExecutor(
            1,
            r -> {
              Thread t = new Thread(r, "alpaca-md-reconnect");
              t.setDaemon(true);
              return t;
            });
    pool.setRemoveOnCancelPolicy(true);
    return pool;
  }

  @Override
  public Optional<Quote> snapshotQuote(String occSymbol) {
    try {
      JsonNode body =
          rest.get()
              .uri("/v1beta1/options/snapshots?symbols={s}", occSymbol)
              .retrieve()
              .body(JsonNode.class);
      if (body == null) {
        return Optional.empty();
      }
      JsonNode latestQuote = body.path("snapshots").path(occSymbol).path("latestQuote");
      if (latestQuote.isMissingNode() || latestQuote.isNull()) {
        return Optional.empty();
      }
      JsonNode bp = latestQuote.path("bp");
      JsonNode ap = latestQuote.path("ap");
      if (!bp.isNumber() || !ap.isNumber()) {
        return Optional.empty();
      }
      BigDecimal bid = bp.decimalValue();
      BigDecimal ask = ap.decimalValue();
      BigDecimal mid =
          bid.add(ask).divide(BigDecimal.valueOf(2), 4, java.math.RoundingMode.HALF_UP);
      OffsetDateTime ts = parseTimestamp(latestQuote.path("t").asText(""));
      return Optional.of(new Quote(occSymbol, bid, mid, ask, ts));
    } catch (HttpStatusCodeException e) {
      log.warn(
          "Alpaca snapshotQuote failed for {}: status={} body={}",
          occSymbol,
          e.getStatusCode().value(),
          e.getResponseBodyAsString());
      return Optional.empty();
    } catch (RuntimeException e) {
      log.warn("Alpaca snapshotQuote failed for {}: {}", occSymbol, e.getMessage());
      return Optional.empty();
    }
  }

  @Override
  public Subscription subscribePremium(String occSymbol, Consumer<Tick> onTick) {
    boolean firstForSymbol =
        bySymbol.computeIfAbsent(occSymbol, k -> new CopyOnWriteArrayList<>()).isEmpty();
    bySymbol.get(occSymbol).add(onTick);
    if (firstForSymbol) {
      sendSubscribe(occSymbol);
    }
    return new AlpacaSubscription(occSymbol, onTick);
  }

  /**
   * Production WS read-loop entry point AND test entry point. Parses one frame's {@code data} field
   * (an array of trade/quote records) and fans out a Tick to all subscribers for the matched
   * symbol.
   *
   * <p>Frame shape (Alpaca options v1beta1):
   *
   * <pre>
   * [
   *   {"T": "t", "S": "AAPL250516C00190000", "p": 1.23, "t": "2026-05-15T17:22:31.123Z"},
   *   {"T": "q", "S": "AAPL250516C00190000", "bp": 1.20, "ap": 1.25, "t": "..."}
   * ]
   * </pre>
   *
   * Trade records ({@code T=="t"}) drive the tick directly via {@code p}. Quote records ({@code
   * T=="q"}) emit a mid computed from {@code bp}+{@code ap}.
   */
  void dispatchWsMessage(String json) {
    JsonNode root;
    try {
      root = mapper.readTree(json);
    } catch (Exception e) {
      log.debug("Alpaca WS: drop unparseable frame: {}", e.getMessage());
      return;
    }
    if (!root.isArray()) {
      return;
    }
    for (JsonNode rec : root) {
      Tick tick = recordToTick(rec);
      if (tick == null) {
        continue;
      }
      List<Consumer<Tick>> listeners = bySymbol.get(tick.occSymbol());
      if (listeners == null) {
        continue;
      }
      for (Consumer<Tick> l : listeners) {
        try {
          l.accept(tick);
        } catch (RuntimeException ignored) {
          // Listener errors are caller's problem; drop them so one bad listener can't poison the
          // whole stream.
        }
      }
    }
  }

  /** Visible for tests. Returns null when the record can't be projected onto a Tick. */
  Tick recordToTick(JsonNode rec) {
    String type = rec.path("T").asText("");
    String sym = rec.path("S").asText("");
    if (sym.isEmpty()) {
      return null;
    }
    OffsetDateTime ts = parseTimestamp(rec.path("t").asText(""));
    if ("t".equals(type)) {
      JsonNode price = rec.path("p");
      if (!price.isNumber()) {
        return null;
      }
      return new Tick(sym, price.decimalValue(), ts);
    }
    if ("q".equals(type)) {
      JsonNode bp = rec.path("bp");
      JsonNode ap = rec.path("ap");
      if (!bp.isNumber() || !ap.isNumber()) {
        return null;
      }
      BigDecimal mid =
          bp.decimalValue()
              .add(ap.decimalValue())
              .divide(BigDecimal.valueOf(2), 4, java.math.RoundingMode.HALF_UP);
      return new Tick(sym, mid, ts);
    }
    return null;
  }

  private static OffsetDateTime parseTimestamp(String raw) {
    if (raw == null || raw.isEmpty()) {
      return OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
    }
    try {
      return OffsetDateTime.parse(raw);
    } catch (RuntimeException e) {
      return OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
    }
  }

  private void sendSubscribe(String occSymbol) {
    WebSocket socket = ensureWs();
    if (socket == null) {
      return; // WS not yet open; reconnect will re-send via activeSymbols snapshot
    }
    String payload =
        "{\"action\":\"subscribe\",\"trades\":[\""
            + occSymbol
            + "\"],\"quotes\":[\""
            + occSymbol
            + "\"]}";
    socket.sendText(payload, true);
  }

  private void sendUnsubscribe(String occSymbol) {
    WebSocket socket = ws;
    if (socket == null) {
      return;
    }
    String payload =
        "{\"action\":\"unsubscribe\",\"trades\":[\""
            + occSymbol
            + "\"],\"quotes\":[\""
            + occSymbol
            + "\"]}";
    try {
      socket.sendText(payload, true);
    } catch (RuntimeException ignored) {
      // Best-effort — the reconnect path won't resubscribe a symbol with zero listeners anyway.
    }
  }

  /** Opens the WS if needed. Returns null when the connect is still in flight or has failed. */
  private WebSocket ensureWs() {
    WebSocket existing = ws;
    if (existing != null) {
      return existing;
    }
    synchronized (wsLock) {
      if (ws != null) {
        return ws;
      }
      try {
        ws = connectBlocking();
        nextBackoff = Duration.ofSeconds(1);
        return ws;
      } catch (RuntimeException e) {
        log.warn("Alpaca WS connect failed: {}", e.getMessage());
        scheduleReconnect();
        return null;
      }
    }
  }

  private WebSocket connectBlocking() {
    CompletableFuture<WebSocket> fut =
        httpClient
            .newWebSocketBuilder()
            .header("APCA-API-KEY-ID", props.apiKeyId())
            .header("APCA-API-SECRET-KEY", props.apiSecretKey())
            .connectTimeout(Duration.ofSeconds(5))
            .buildAsync(URI.create(props.dataWsUrl()), new WsListener());
    try {
      return fut.get(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new RuntimeException("Alpaca WS connect failed", e);
    }
  }

  private void scheduleReconnect() {
    if (!reconnectInFlight.compareAndSet(false, true)) {
      return;
    }
    Duration delay = nextBackoff;
    nextBackoff = nextBackoff.multipliedBy(2);
    if (nextBackoff.compareTo(MAX_BACKOFF) > 0) {
      nextBackoff = MAX_BACKOFF;
    }
    scheduler.schedule(
        () -> {
          reconnectInFlight.set(false);
          synchronized (wsLock) {
            ws = null;
          }
          WebSocket socket = ensureWs();
          if (socket == null) {
            return;
          }
          // Re-send subscribe for every symbol that still has a listener. Lossy: any tick that
          // landed during the gap is gone.
          Set<String> activeSymbols = new LinkedHashSet<>(bySymbol.keySet());
          for (String s : activeSymbols) {
            if (!bySymbol.get(s).isEmpty()) {
              sendSubscribe(s);
            }
          }
        },
        delay.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  @PreDestroy
  void shutdown() {
    scheduler.shutdownNow();
    WebSocket socket = ws;
    if (socket != null) {
      try {
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
      } catch (RuntimeException ignored) {
        // Best-effort
      }
    }
  }

  /** WebSocket listener wires raw text frames into {@link #dispatchWsMessage}. */
  private final class WsListener implements WebSocket.Listener {
    private final StringBuilder buf = new StringBuilder();

    @Override
    public CompletableFuture<?> onText(WebSocket socket, CharSequence data, boolean last) {
      buf.append(data);
      if (last) {
        String frame = buf.toString();
        buf.setLength(0);
        dispatchWsMessage(frame);
      }
      socket.request(1);
      return null;
    }

    @Override
    public void onError(WebSocket socket, Throwable error) {
      log.warn("Alpaca WS error: {}", error.getMessage());
      scheduleReconnect();
    }

    @Override
    public CompletableFuture<?> onClose(WebSocket socket, int statusCode, String reason) {
      log.info("Alpaca WS closed status={} reason={}", statusCode, reason);
      scheduleReconnect();
      return null;
    }
  }

  private final class AlpacaSubscription implements Subscription {
    private final String id = UUID.randomUUID().toString();
    private final String symbol;
    private final Consumer<Tick> listener;

    AlpacaSubscription(String symbol, Consumer<Tick> listener) {
      this.symbol = symbol;
      this.listener = listener;
    }

    @Override
    public String subscriptionId() {
      return id;
    }

    @Override
    public void close() {
      List<Consumer<Tick>> listeners = bySymbol.get(symbol);
      if (listeners == null) {
        return;
      }
      listeners.remove(listener);
      if (listeners.isEmpty()) {
        bySymbol.remove(symbol);
        sendUnsubscribe(symbol);
      }
    }
  }
}
