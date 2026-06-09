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
import java.util.Map;
import java.util.Optional;
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
import org.springframework.beans.factory.annotation.Autowired;
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

  @Autowired
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
    // Alpaca's options data API requires the COMPACT OCC (regex ^[A-Z]{1,5}\d{6,7}[CP]\d{8}$ — no
    // space-padding); our internal canonical form is space-padded (e.g. "SPY   260609P00731000").
    // Strip padding for both the request symbol AND the response key (Alpaca echoes the symbol it
    // received), but return the Quote keyed by the caller's original symbol. Without this, every
    // snapshot 400s ("invalid symbol") and bounded-limit flatten/reprice silently falls back to
    // marketable. cf. the same padded-vs-compact class in JooqOrderIntentJournal / commit 16e4c6e.
    String compact = occSymbol.replace(" ", "");
    try {
      JsonNode body =
          rest.get()
              .uri("/v1beta1/options/snapshots?symbols={s}", compact)
              .retrieve()
              .body(JsonNode.class);
      if (body == null) {
        return Optional.empty();
      }
      JsonNode latestQuote = body.path("snapshots").path(compact).path("latestQuote");
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
      OffsetDateTime ts = parseTimestamp(latestQuote.path("t").asText(""));
      return Optional.of(new Quote(occSymbol, bid, midPrice(bid, ask), ask, ts));
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
    List<Consumer<Tick>> listeners =
        bySymbol.computeIfAbsent(occSymbol, k -> new CopyOnWriteArrayList<>());
    // Lock the per-symbol list so the isEmpty()→add→sendSubscribe compound is atomic.
    // Without this, two concurrent first-subscribers both see isEmpty()==true and both call
    // sendSubscribe — duplicate WS subscribes. The lock is uncontended in steady state
    // (subscribe/unsubscribe are rare) and scoped to one symbol, so it doesn't serialize fan-out.
    synchronized (listeners) {
      boolean firstForSymbol = listeners.isEmpty();
      listeners.add(onTick);
      if (firstForSymbol) {
        sendSubscribe(occSymbol);
      }
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
      return new Tick(sym, midPrice(bp.decimalValue(), ap.decimalValue()), ts);
    }
    return null;
  }

  private static BigDecimal midPrice(BigDecimal bid, BigDecimal ask) {
    return bid.add(ask).divide(BigDecimal.valueOf(2), 4, java.math.RoundingMode.HALF_UP);
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

  /** Package-private so tests can spy on the per-symbol upstream subscribe count. */
  void sendSubscribe(String occSymbol) {
    WebSocket socket = ensureWs();
    if (socket == null) {
      // WS not yet open; reconnect will re-send via activeSymbols snapshot.
      return;
    }
    socket.sendText(subscribeAction("subscribe", occSymbol), true);
  }

  private void sendUnsubscribe(String occSymbol) {
    WebSocket socket = ws;
    if (socket == null) {
      return;
    }
    try {
      socket.sendText(subscribeAction("unsubscribe", occSymbol), true);
    } catch (RuntimeException ignored) {
      // Best-effort — the reconnect path won't resubscribe a symbol with zero listeners anyway.
    }
  }

  /**
   * Build the Alpaca subscribe/unsubscribe action frame via Jackson so the symbol value is
   * JSON-escaped, not concatenated. OCC symbols today are {@code [A-Z0-9 ]}-only, so the legacy
   * concatenation produced valid JSON — but a future symbol scheme with a quote or backslash would
   * silently corrupt the wire frame. Jackson is the safe form.
   */
  private String subscribeAction(String action, String occSymbol) {
    try {
      return mapper.writeValueAsString(
          Map.of("action", action, "trades", List.of(occSymbol), "quotes", List.of(occSymbol)));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      // Map.of with only String/List values never throws — this is unreachable.
      throw new IllegalStateException("failed to serialize subscribe frame for " + occSymbol, e);
    }
  }

  /**
   * Opens the WS if needed. Returns null when the connect is still in flight or has failed.
   *
   * <p>Package-private so tests can stub it without standing up a real WS endpoint.
   */
  WebSocket ensureWs() {
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

  /** Package-private so tests can drive the reconnect path directly. */
  void scheduleReconnect() {
    if (!reconnectInFlight.compareAndSet(false, true)) {
      return;
    }
    Duration delay = nextBackoff;
    nextBackoff = nextBackoff.multipliedBy(2);
    if (nextBackoff.compareTo(MAX_BACKOFF) > 0) {
      nextBackoff = MAX_BACKOFF;
    }
    scheduler.schedule(this::runReconnect, delay.toMillis(), TimeUnit.MILLISECONDS);
  }

  /** Package-private so tests can drive the reconnect body deterministically. */
  void runReconnect() {
    // Null the socket BEFORE clearing the in-flight guard. If a stale WsListener.onClose
    // fires (originating from the just-failed socket) while we're mid-reconnect, it must
    // see reconnectInFlight==true and skip — otherwise it races us to open a second socket.
    synchronized (wsLock) {
      ws = null;
    }
    reconnectInFlight.set(false);
    WebSocket socket = ensureWs();
    if (socket == null) {
      return;
    }
    // Re-send subscribe for every symbol that still has a listener. Lossy: any tick that
    // landed during the gap is gone. Iterate a snapshot so concurrent close()s during the
    // resubscribe walk can't NPE.
    for (String s : new LinkedHashSet<>(bySymbol.keySet())) {
      List<Consumer<Tick>> listeners = bySymbol.get(s);
      if (listeners != null && !listeners.isEmpty()) {
        sendSubscribe(s);
      }
    }
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
      // Mirror the subscribe path: hold the per-symbol lock so a concurrent subscribe can't see
      // empty→removed and re-create the listener list between our remove() and bySymbol.remove().
      //
      // Note: lock-on-listeners-list does not by itself protect against a different thread that
      // just inserted a brand-new list under the same key via computeIfAbsent — that thread holds
      // a different list object and never touches our monitor. The two-arg remove(key, expected)
      // collapses that race by deleting the entry only if the current value still matches the list
      // we just emptied. If it's been replaced by a fresh list, leave it alone.
      synchronized (listeners) {
        listeners.remove(listener);
        if (listeners.isEmpty()) {
          bySymbol.remove(symbol, listeners);
          sendUnsubscribe(symbol);
        }
      }
    }
  }
}
