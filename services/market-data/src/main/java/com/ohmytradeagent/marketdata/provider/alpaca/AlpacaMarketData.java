package com.ohmytradeagent.marketdata.provider.alpaca;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.marketdata.health.FeedHealth;
import com.ohmytradeagent.marketdata.provider.MarketDataProvider;
import com.ohmytradeagent.marketdata.provider.PremiumFeedStatus;
import com.ohmytradeagent.marketdata.provider.Quote;
import com.ohmytradeagent.marketdata.provider.Subscription;
import com.ohmytradeagent.marketdata.provider.Tick;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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
 * <p>Option premium: a REST POLL, not a stream. On {@link #subscribePremium} the first subscriber
 * per OCC starts a fixed-rate {@link #snapshotQuote} poll ({@link #startPremiumPoll}); the last
 * subscriber's {@link Subscription#close()} cancels it ({@link #stopPremiumPoll}). The poll is
 * fail-soft ({@link #pollOnce}): a missing/429/5xx snapshot emits no tick and never cancels the
 * task. (The Alpaca v1beta1 options WS was msgpack/binary + message-auth and never delivered ticks,
 * so it was removed.)
 *
 * <p>Stock trade stream: the real-time equity feed still uses Java's {@code
 * java.net.http.WebSocket} (no Netty/Spring-WebSocket dep) with in-band message auth; see {@link
 * #ensureStockWs}.
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
  private final FeedHealth feedHealth;

  /** Subscriber registry: symbol -> {listener, ...}. */
  private final ConcurrentHashMap<String, List<Consumer<Tick>>> bySymbol =
      new ConcurrentHashMap<>();

  // Option premium is a REST POLL (not a WS): one scheduled snapshotQuote task per distinct open
  // OCC,
  // started on the first subscriber and cancelled on the last. The dead options WS (header-auth +
  // msgpack-unhandled — it never delivered ticks) was removed.
  private final ConcurrentHashMap<String, ScheduledFuture<?>> premiumPolls =
      new ConcurrentHashMap<>();
  // Consecutive failed polls PER OCC (a healthy contract clears its own count on success). The
  // OPTION feed is marked disconnected only when EVERY subscribed OCC is past the threshold (whole
  // feed dead) — a single contract's transient miss must not flip the aggregate gauge, and one
  // healthy contract ticking proves the feed is alive.
  private final ConcurrentHashMap<String, Integer> optionPollFailures = new ConcurrentHashMap<>();

  /**
   * Per-OCC premium-poll liveness (#717). Keyed like {@link #bySymbol} (space-padded OCC). Entries
   * are created on the first successful poll and dropped when the last subscriber leaves.
   */
  private final ConcurrentHashMap<String, Liveness> premiumLiveness = new ConcurrentHashMap<>();

  private static final int OPTION_POLL_FAIL_THRESHOLD = 3;

  /** Equity subscriber registry: ticker -> {listener, ...}. Separate WS endpoint from options. */
  private final ConcurrentHashMap<String, List<Consumer<Tick>>> byTicker =
      new ConcurrentHashMap<>();

  // Single-tick outlier guard state (mirrors byTicker). A gross/phantom equity print — the
  // NVDA-type ~2%+ single-tick jump that reverts — must never fan out to a trigger subscriber,
  // while a GENUINE sustained fast move (two agreeing prints) is not false-rejected. Feed-layer
  // only: this drops at the source; it does not touch evaluation/EntryStateMachine.
  private final ConcurrentHashMap<String, BigDecimal> lastAcceptedPrice = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, BigDecimal> pendingCandidate = new ConcurrentHashMap<>();
  private static final BigDecimal MAX_DEVIATION_PCT = new BigDecimal("0.02");

  // ---- Option-premium outlier guard (the F1 equity guard's sibling; premium had NONE) ----
  //
  // Keyed by OCC, deliberately separate from the equity maps above: those are keyed by TICKER and a
  // shared map would collide an option with its underlying.
  private final ConcurrentHashMap<String, BigDecimal> lastAcceptedPremium =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, BigDecimal> pendingPremiumCandidate =
      new ConcurrentHashMap<>();

  /**
   * The retrieved_at of the last quote we ACTED on, per OCC. This is the load-bearing half of the
   * guard, and the reason the equity shape alone is not enough here.
   *
   * <p>{@link #pollOnce} emits on EVERY poll with no dedup, and {@link #snapshotQuote} takes the
   * timestamp from Alpaca's `latestQuote.t` — the QUOTE's own stamp, not the poll's. So when the
   * NBBO stops updating, consecutive polls return the SAME quote and produce byte-identical ticks.
   * That is not a rare case: it is definitionally what happens when a bid is withdrawn and nothing
   * requotes — exactly the blown book this guard exists to reject. Without this check, "two
   * agreeing prints" degenerates into resampling one print twice, and corroboration proves nothing.
   */
  private final ConcurrentHashMap<String, OffsetDateTime> lastQuoteStamp =
      new ConcurrentHashMap<>();

  /**
   * Deviation band for option PREMIUM, far wider than the equity 2%. Option premium legitimately
   * moves tens of percent between two 2s polls on a short-dated contract, so an equity-tight band
   * would reject the real market constantly. 35% is chosen to sit ABOVE ordinary fast-move
   * behaviour and BELOW the blown-book signature this is built for: a withdrawn bid on a 3.90x4.10
   * book takes the mid from 4.00 to ~2.08, a ~48% collapse.
   *
   * <p>Out-of-band does NOT mean dropped forever — it means held for corroboration, so a genuine
   * violent move is admitted one poll (~2s) later rather than lost.
   */
  private static final BigDecimal MAX_PREMIUM_DEVIATION_PCT = new BigDecimal("0.35");

  private final Object stockWsLock = new Object();

  // CONNECTION-LIMIT CAVEAT: this stock stream is the ONLY market-data WS now (the options premium
  // feed is a REST poll, not a socket). This singleton fans out one WS to all equity subscribers,
  // so
  // the stocks connection count is fixed at 1 regardless of strategy/leg count — and ONLY while
  // market-data runs a SINGLE replica. VERIFIED 2026-06-20 (scripts/alpaca-ws-conn-check.py): the
  // Alpaca limit is ONE connection PER ENDPOINT, not account-wide — stocks (/v2) and options
  // (/v1beta1) coexist on one key; a 2nd connection to the SAME endpoint gets 406 and is refused
  // (the existing one survives). So a 2nd market-data replica's duplicate connections would be
  // 406'd — keep market-data a single replica.
  private volatile WebSocket stockWs;
  private final AtomicBoolean stockReconnectInFlight = new AtomicBoolean(false);
  private volatile Duration stockNextBackoff = Duration.ofSeconds(1);
  // Alpaca's v2 stocks DATA stream is authenticated by an in-band {"action":"auth"} MESSAGE (NOT
  // HTTP headers) and rejects subscribes until it replies {"T":"success","msg":"authenticated"}.
  // Subscribes are gated on this flag; the authenticated control frame applies the byTicker set.
  // Package-private for test assertions. Reset on every (re)connect/close.
  volatile boolean stockAuthenticated;

  @Autowired
  public AlpacaMarketData(
      RestClient alpacaMarketDataRestClient,
      ObjectMapper objectMapper,
      AlpacaMarketDataProperties props,
      FeedHealth feedHealth) {
    this(
        alpacaMarketDataRestClient,
        objectMapper,
        props,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
        defaultScheduler(),
        feedHealth);
  }

  /**
   * Visible for tests that want to inject a deterministic scheduler / fake HttpClient. Uses a
   * standalone {@link FeedHealth} (its own registry) so existing 5-arg callers are unaffected.
   */
  AlpacaMarketData(
      RestClient alpacaMarketDataRestClient,
      ObjectMapper objectMapper,
      AlpacaMarketDataProperties props,
      HttpClient httpClient,
      ScheduledExecutorService scheduler) {
    this(
        alpacaMarketDataRestClient,
        objectMapper,
        props,
        httpClient,
        scheduler,
        new FeedHealth(new SimpleMeterRegistry()));
  }

  AlpacaMarketData(
      RestClient alpacaMarketDataRestClient,
      ObjectMapper objectMapper,
      AlpacaMarketDataProperties props,
      HttpClient httpClient,
      ScheduledExecutorService scheduler,
      FeedHealth feedHealth) {
    this.rest = alpacaMarketDataRestClient;
    this.mapper = objectMapper;
    this.props = props;
    this.httpClient = httpClient;
    this.scheduler = scheduler;
    this.feedHealth = feedHealth;
  }

  private static ScheduledExecutorService defaultScheduler() {
    // Pool > 1 so a slow/blocking premium-poll snapshot for one OCC cannot serialize the others (or
    // the stock reconnect). Each open option position gets its own fixed-rate poll task.
    ScheduledThreadPoolExecutor pool =
        new ScheduledThreadPoolExecutor(
            4,
            r -> {
              Thread t = new Thread(r, "alpaca-md-poll");
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
  public Optional<BigDecimal> snapshotEquityPrice(String ticker) {
    // Last-trade price from Alpaca's stock snapshot. Display-only (dashboard underlying spot); not
    // a
    // trigger input, so unlike subscribeEquity it has no RTH/entitlement gate. Fail-open: any
    // error/missing field -> empty so the dashboard shows "-" rather than blocking the response.
    try {
      JsonNode body =
          rest.get().uri("/v2/stocks/{s}/snapshot", ticker).retrieve().body(JsonNode.class);
      if (body == null) {
        return Optional.empty();
      }
      JsonNode p = body.path("latestTrade").path("p");
      if (!p.isNumber()) {
        return Optional.empty();
      }
      return Optional.of(p.decimalValue());
    } catch (HttpStatusCodeException e) {
      log.warn(
          "Alpaca snapshotEquityPrice failed for {}: status={} body={}",
          ticker,
          e.getStatusCode().value(),
          e.getResponseBodyAsString());
      return Optional.empty();
    } catch (RuntimeException e) {
      log.warn("Alpaca snapshotEquityPrice failed for {}: {}", ticker, e.getMessage());
      return Optional.empty();
    }
  }

  @Override
  public Subscription subscribePremium(String occSymbol, Consumer<Tick> onTick) {
    List<Consumer<Tick>> listeners =
        bySymbol.computeIfAbsent(occSymbol, k -> new CopyOnWriteArrayList<>());
    // Lock the per-symbol list so the isEmpty()→add→startPremiumPoll compound is atomic: two
    // concurrent first-subscribers must not both start a poll. Uncontended in steady state and
    // scoped to one symbol, so it doesn't serialize fan-out.
    synchronized (listeners) {
      boolean firstForSymbol = listeners.isEmpty();
      listeners.add(onTick);
      if (firstForSymbol) {
        startPremiumPoll(occSymbol);
      }
    }
    return new AlpacaSubscription(occSymbol, onTick);
  }

  @Override
  public Subscription subscribeEquity(String ticker, Consumer<Tick> onTick) {
    // LIVE-USE GATE: the real-time stock feed is opt-in. With no explicit stock WS URL (and no
    // stock-feed) configured, fail closed: a delayed/wrong feed must never silently drive a
    // price-level trigger. Emit a LOUD audit and refuse to connect.
    if (props.effectiveStockDataWsUrl().isEmpty()) {
      log.error(
          "AUDIT stock-feed-gated: market-data.alpaca.stock-data-ws-url (and stock-feed) unset; "
              + "refusing to subscribeEquity ticker={} (fail-closed, no connect). Set a real-time "
              + "stock feed to enable.",
          ticker);
      throw new StockFeedGatedException("stock data WS not configured; equity subscription gated");
    }
    List<Consumer<Tick>> listeners =
        byTicker.computeIfAbsent(ticker, k -> new CopyOnWriteArrayList<>());
    synchronized (listeners) {
      boolean firstForTicker = listeners.isEmpty();
      listeners.add(onTick);
      if (firstForTicker) {
        sendStockSubscribe(ticker);
      }
    }
    return new AlpacaEquitySubscription(ticker, onTick);
  }

  /**
   * Stocks-WS read-loop + test entry point. Parses one frame's array of trade/quote/status records
   * and fans out a {@link Tick} (last trade price) to all equity subscribers for the matched
   * ticker. Halted/stale prints are dropped (never emitted).
   *
   * <p>Frame shape (Alpaca stocks v2):
   *
   * <pre>
   * [ {"T": "t", "S": "NVDA", "p": 140.12, "t": "2026-06-20T13:31:00.1Z", "c": ["@"]} ]
   * </pre>
   *
   * A trade record carrying a halt/stale condition code (e.g. {@code "H"}/{@code "P"}) is dropped.
   * Trade-status records ({@code T=="status"}) with a halt status are advisory only and emit no
   * tick.
   */
  void dispatchStockWsMessage(String json) {
    JsonNode root;
    try {
      root = mapper.readTree(json);
    } catch (Exception e) {
      log.debug("Alpaca stock WS: drop unparseable frame: {}", e.getMessage());
      return;
    }
    if (!root.isArray()) {
      return;
    }
    for (JsonNode rec : root) {
      String type = rec.path("T").asText("");
      // Control frames (previously dropped silently, which hid the rejected auth/subscribe).
      if ("success".equals(type)) {
        handleStockControlSuccess(rec.path("msg").asText(""));
        continue;
      }
      if ("error".equals(type)) {
        log.error(
            "AUDIT stock-ws-auth-error: code={} msg={}",
            rec.path("code").asText(""),
            rec.path("msg").asText(""));
        continue;
      }
      if ("subscription".equals(type)) {
        log.debug("Alpaca stock WS: subscription confirmed {}", rec);
        continue;
      }
      Tick tick = recordToEquityTick(rec);
      if (tick == null) {
        continue;
      }
      feedHealth.recordTick(FeedHealth.Feed.EQUITY);
      List<Consumer<Tick>> listeners = byTicker.get(tick.occSymbol());
      if (listeners == null) {
        continue;
      }
      for (Consumer<Tick> l : listeners) {
        try {
          l.accept(tick);
        } catch (RuntimeException ignored) {
          // One bad listener must not poison the stream.
        }
      }
    }
  }

  /**
   * Handles a stocks-stream {@code {"T":"success"}} control frame. The {@code authenticated} reply
   * is the gate: only then can subscriptions be sent and only then is the feed "connected" for
   * health.
   */
  private void handleStockControlSuccess(String msg) {
    if ("authenticated".equals(msg)) {
      feedHealth.markConnected(FeedHealth.Feed.EQUITY);
      log.info("Alpaca stock WS authenticated; subscribing {} ticker(s)", byTicker.size());
      // Set the flag + subscribe under the lock so a ticker added concurrently in subscribeEquity
      // is
      // sent by exactly one path (here or its own sendStockSubscribe), never dropped.
      synchronized (stockWsLock) {
        stockAuthenticated = true;
        resubscribeAllStocks();
      }
    } else {
      // The initial {"msg":"connected"} greeting precedes auth; nothing to do.
      log.debug("Alpaca stock WS: {}", msg);
    }
  }

  /**
   * Visible for tests. Projects a stocks-stream record onto a {@link Tick} (last trade price), or
   * null when it isn't a usable trade (non-trade type, missing price, or a halted/stale condition).
   */
  Tick recordToEquityTick(JsonNode rec) {
    String type = rec.path("T").asText("");
    if (!"t".equals(type)) {
      return null;
    }
    String sym = rec.path("S").asText("");
    if (sym.isEmpty()) {
      return null;
    }
    if (isHaltedOrStale(rec)) {
      return null;
    }
    JsonNode price = rec.path("p");
    if (!price.isNumber()) {
      return null;
    }
    BigDecimal p = price.decimalValue();
    if (!acceptEquityPrice(sym, p)) {
      return null;
    }
    return new Tick(sym, p, parseTimestamp(rec.path("t").asText("")));
  }

  /**
   * Single-tick outlier guard for the equity trigger feed. A first print seeds the reference; an
   * in-band print ({@code <=2%} vs the last accepted price) is accepted and advances the reference;
   * an out-of-band print is DROPPED and held as a candidate, and only accepted once a SECOND print
   * corroborates it (two agreeing prints = a real sustained move). This keeps a lone gross/phantom
   * spike that reverts from ever fanning out, without false-rejecting a genuine fast breakout.
   * Returns true to accept (and emit), false to drop.
   */
  private boolean acceptEquityPrice(String ticker, BigDecimal price) {
    // A non-positive equity price is itself aberrant: drop it and never let it seed the reference
    // (a zero reference would make every subsequent deviation() divide by zero and silently blind
    // the ticker's feed).
    if (price.signum() <= 0) {
      log.warn(
          "AUDIT stock-tick-outlier-rejected: ticker={} last={} ref={} devPct=non-positive",
          ticker,
          price,
          lastAcceptedPrice.get(ticker));
      return false;
    }
    BigDecimal ref = lastAcceptedPrice.get(ticker);
    if (ref == null) {
      acceptEquity(ticker, price);
      return true;
    }
    BigDecimal devFromRef = deviation(price, ref);
    if (devFromRef.compareTo(MAX_DEVIATION_PCT) <= 0) {
      acceptEquity(ticker, price);
      return true;
    }
    BigDecimal pending = pendingCandidate.get(ticker);
    if (pending != null && deviation(price, pending).compareTo(MAX_DEVIATION_PCT) <= 0) {
      acceptEquity(ticker, price);
      return true;
    }
    pendingCandidate.put(ticker, price);
    log.warn(
        "AUDIT stock-tick-outlier-rejected: ticker={} last={} ref={} devPct={}",
        ticker,
        price,
        ref,
        devFromRef.movePointRight(2).setScale(4, java.math.RoundingMode.HALF_UP));
    return false;
  }

  /**
   * Feed-layer acceptance for an option-premium quote — the sibling {@link #acceptEquityPrice} has
   * had since the 2026-07-29 false-trigger incident, and premium never did.
   *
   * <p>Placed HERE, in the poll fan-out, and deliberately NOT in {@link #snapshotQuote}: that
   * method is shared with {@code GetOptionQuoteActivity}, whose consumers include the account
   * kill-switch's MTM read — and that path fail-closes on a single missing quote. Withholding a
   * quote there would re-create a known outage. Filtering the TICK stream affects only trail/stop
   * evaluation.
   *
   * @return true when the tick should fan out to subscribers
   */
  private boolean acceptPremiumQuote(String occSymbol, Quote q) {
    BigDecimal premium = q.mid();
    // A non-positive premium is not a price, and must never seed the reference: a zero reference
    // makes every later deviation() divide by zero and silently blinds the contract's feed.
    if (premium == null || premium.signum() <= 0) {
      log.warn(
          "AUDIT premium-tick-rejected: occ={} reason=non-positive-premium premium={}",
          occSymbol,
          premium);
      return false;
    }
    // A withdrawn/absent bid is the realistic degenerate case, not a crossed book. The mid is then
    // (0+ask)/2 — an arithmetic artifact, not a price anything could be sold into, and it halves
    // the premium in one tick. Reject before it can anchor a trail or a stop.
    if (q.bid() == null || q.bid().signum() <= 0) {
      log.warn(
          "AUDIT premium-tick-rejected: occ={} reason=no-bid bid={} ask={} mid={}",
          occSymbol,
          q.bid(),
          q.ask(),
          premium);
      return false;
    }
    // Same quote, resampled by the next poll — one observation, not two. See lastQuoteStamp.
    OffsetDateTime prevStamp = lastQuoteStamp.get(occSymbol);
    if (prevStamp != null && q.retrievedAt() != null && prevStamp.isEqual(q.retrievedAt())) {
      return false;
    }

    BigDecimal ref = lastAcceptedPremium.get(occSymbol);
    if (ref == null) {
      acceptPremium(occSymbol, premium, q.retrievedAt());
      return true;
    }
    if (deviation(premium, ref).compareTo(MAX_PREMIUM_DEVIATION_PCT) <= 0) {
      acceptPremium(occSymbol, premium, q.retrievedAt());
      return true;
    }
    // Out of band. Admit it only if a LATER, DISTINCT quote agrees with the held candidate — the
    // agreement is against the CANDIDATE, not the stale reference. Requiring only "also far from
    // the reference" would let two unrelated phantoms corroborate each other.
    BigDecimal pending = pendingPremiumCandidate.get(occSymbol);
    if (pending != null && deviation(premium, pending).compareTo(MAX_PREMIUM_DEVIATION_PCT) <= 0) {
      acceptPremium(occSymbol, premium, q.retrievedAt());
      return true;
    }
    pendingPremiumCandidate.put(occSymbol, premium);
    // Stamp it even though it was rejected: the candidate came from THIS quote, so the next poll
    // resampling the same quote must not be mistaken for the corroborating second observation.
    lastQuoteStamp.put(occSymbol, q.retrievedAt());
    log.warn(
        "AUDIT premium-tick-rejected: occ={} reason=outlier premium={} ref={} devPct={}",
        occSymbol,
        premium,
        ref,
        deviation(premium, ref).movePointRight(2).setScale(4, java.math.RoundingMode.HALF_UP));
    return false;
  }

  private void acceptPremium(String occSymbol, BigDecimal premium, OffsetDateTime stamp) {
    lastAcceptedPremium.put(occSymbol, premium);
    pendingPremiumCandidate.remove(occSymbol);
    if (stamp != null) {
      lastQuoteStamp.put(occSymbol, stamp);
    }
  }

  private void acceptEquity(String ticker, BigDecimal price) {
    lastAcceptedPrice.put(ticker, price);
    pendingCandidate.remove(ticker);
  }

  private static BigDecimal deviation(BigDecimal price, BigDecimal ref) {
    return price.subtract(ref).abs().divide(ref, 10, java.math.RoundingMode.HALF_UP);
  }

  /**
   * A trade print is halted/stale when its condition list ({@code c}) carries a halt/stale code.
   * Alpaca SIP/IEX trade conditions: {@code "H"} (halt), {@code "P"} (prior reference / late),
   * {@code "Z"} (sold last / out of sequence). We treat any of these as not safe to trigger on.
   */
  private static boolean isHaltedOrStale(JsonNode rec) {
    JsonNode conds = rec.path("c");
    if (!conds.isArray()) {
      return false;
    }
    for (JsonNode c : conds) {
      String code = c.asText("");
      if ("H".equals(code) || "P".equals(code) || "Z".equals(code)) {
        return true;
      }
    }
    return false;
  }

  /** Package-private so tests can spy on the per-ticker upstream subscribe count. */
  void sendStockSubscribe(String ticker) {
    WebSocket socket = ensureStockWs();
    if (socket == null) {
      return;
    }
    // Lock so the auth flag check + send is ordered against handleStockControlSuccess (which sets
    // the
    // flag and resubscribes under the same lock). A ticker registered just as auth completes is
    // then
    // sent by exactly one of the two paths, never dropped. stockWsLock is reentrant w.r.t.
    // ensureStockWs above.
    synchronized (stockWsLock) {
      if (!stockAuthenticated) {
        // Pre-auth: the ticker is already in byTicker and gets subscribed when the `authenticated`
        // control frame arrives (resubscribeAllStocks). Sending now would be ignored by Alpaca.
        return;
      }
      socket.sendText(subscribeAction("subscribe", ticker), true);
    }
  }

  /**
   * Builds the v2 stocks DATA-stream auth frame: {@code {"action":"auth","key":..,"secret":..}}.
   */
  String authAction() {
    try {
      return mapper.writeValueAsString(
          Map.of("action", "auth", "key", props.apiKeyId(), "secret", props.apiSecretKey()));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("failed to serialize stock auth frame", e);
    }
  }

  /** Subscribes every currently-registered equity ticker (called once on `authenticated`). */
  private void resubscribeAllStocks() {
    for (String t : new LinkedHashSet<>(byTicker.keySet())) {
      List<Consumer<Tick>> listeners = byTicker.get(t);
      if (listeners != null && !listeners.isEmpty()) {
        sendStockSubscribe(t);
      }
    }
  }

  private void sendStockUnsubscribe(String ticker) {
    WebSocket socket = stockWs;
    if (socket == null) {
      return;
    }
    try {
      socket.sendText(subscribeAction("unsubscribe", ticker), true);
    } catch (RuntimeException ignored) {
      // Best-effort.
    }
  }

  /**
   * Opens the stocks WS if needed. Returns null when the connect is in flight or has failed.
   * Package-private so tests can stub it without a real endpoint.
   */
  WebSocket ensureStockWs() {
    WebSocket existing = stockWs;
    if (existing != null) {
      return existing;
    }
    synchronized (stockWsLock) {
      if (stockWs != null) {
        return stockWs;
      }
      try {
        stockAuthenticated = false;
        stockWs = connectStockBlocking();
        stockNextBackoff = Duration.ofSeconds(1);
        // Authenticate in-band; FeedHealth.markConnected(EQUITY) is deferred to the `authenticated`
        // control frame (handleStockControlSuccess), which is when data can actually flow.
        stockWs.sendText(authAction(), true);
        return stockWs;
      } catch (RuntimeException e) {
        log.error("AUDIT stock-ws-connect-failed: {}", e.getMessage());
        scheduleStockReconnect();
        return null;
      }
    }
  }

  private WebSocket connectStockBlocking() {
    String url =
        props
            .effectiveStockDataWsUrl()
            .orElseThrow(
                () -> new IllegalStateException("stock data WS not configured; cannot connect"));
    // No HTTP-header auth: the v2 stocks DATA stream authenticates via the in-band
    // {"action":"auth"}
    // message sent in ensureStockWs() after the socket opens. Header auth leaves the socket
    // connected-but-unauthenticated, so Alpaca honors no subscriptions and pushes zero data.
    CompletableFuture<WebSocket> fut =
        httpClient
            .newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .buildAsync(URI.create(url), new StockWsListener());
    try {
      return fut.get(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new RuntimeException("Alpaca stock WS connect failed", e);
    }
  }

  /** Package-private so tests can drive the reconnect path directly. */
  void scheduleStockReconnect() {
    if (!stockReconnectInFlight.compareAndSet(false, true)) {
      return;
    }
    Duration delay = stockNextBackoff;
    stockNextBackoff = stockNextBackoff.multipliedBy(2);
    if (stockNextBackoff.compareTo(MAX_BACKOFF) > 0) {
      stockNextBackoff = MAX_BACKOFF;
    }
    scheduler.schedule(this::runStockReconnect, delay.toMillis(), TimeUnit.MILLISECONDS);
  }

  /** Package-private so tests can drive the reconnect body deterministically. */
  void runStockReconnect() {
    synchronized (stockWsLock) {
      stockWs = null;
    }
    stockAuthenticated = false;
    stockReconnectInFlight.set(false);
    // ensureStockWs reconnects + re-sends the auth message; the `authenticated` control frame then
    // re-subscribes every byTicker symbol (resubscribeAllStocks). No explicit re-subscribe here.
    ensureStockWs();
  }

  /**
   * Accumulate one message's fragments into {@code buf}, returning the complete frame (and
   * resetting {@code buf}) only on the final fragment; otherwise null. The buffer is snapshotted
   * and cleared BEFORE the caller dispatches, so a throwing dispatch can never leave a partial
   * frame that corrupts the next message's reassembly. {@code java.net.http.WebSocket} delivers
   * {@code onText} sequentially per connection, so this needs no locking — but it must be
   * message-local-safe across the fragment boundary, which this is.
   */
  static String accumulateFrame(StringBuilder buf, CharSequence data, boolean last) {
    buf.append(data);
    if (!last) {
      return null;
    }
    String frame = buf.toString();
    buf.setLength(0);
    return frame;
  }

  /** Stocks-WS listener wires raw text frames into {@link #dispatchStockWsMessage}. */
  private final class StockWsListener implements WebSocket.Listener {
    private final StringBuilder buf = new StringBuilder();

    @Override
    public CompletableFuture<?> onText(WebSocket socket, CharSequence data, boolean last) {
      String frame = accumulateFrame(buf, data, last);
      if (frame != null) {
        dispatchStockWsMessage(frame);
      }
      socket.request(1);
      return null;
    }

    @Override
    public void onError(WebSocket socket, Throwable error) {
      log.error("AUDIT stock-ws-error: {}", error.getMessage());
      stockAuthenticated = false;
      feedHealth.markDisconnected(FeedHealth.Feed.EQUITY);
      scheduleStockReconnect();
    }

    @Override
    public CompletableFuture<?> onClose(WebSocket socket, int statusCode, String reason) {
      log.error("AUDIT stock-ws-closed status={} reason={}", statusCode, reason);
      stockAuthenticated = false;
      feedHealth.markDisconnected(FeedHealth.Feed.EQUITY);
      scheduleStockReconnect();
      return null;
    }
  }

  private final class AlpacaEquitySubscription implements Subscription {
    private final String id = UUID.randomUUID().toString();
    private final String ticker;
    private final Consumer<Tick> listener;

    AlpacaEquitySubscription(String ticker, Consumer<Tick> listener) {
      this.ticker = ticker;
      this.listener = listener;
    }

    @Override
    public String subscriptionId() {
      return id;
    }

    @Override
    public void close() {
      List<Consumer<Tick>> listeners = byTicker.get(ticker);
      if (listeners == null) {
        return;
      }
      synchronized (listeners) {
        listeners.remove(listener);
        if (listeners.isEmpty()) {
          byTicker.remove(ticker, listeners);
          sendStockUnsubscribe(ticker);
        }
      }
    }
  }

  /**
   * One option-premium poll iteration: REST snapshot -> {@code Tick(occ, mid, bid, ask)} -> fan out
   * to the {@code bySymbol} listeners. Package-private so tests drive it directly. FAIL-SOFT: a
   * missing/empty/throwing snapshot emits NO tick and does NOT cancel the recurring task (the
   * scheduler keeps polling). The whole body is catch-all wrapped so a scheduled task can never
   * die.
   */
  void pollOnce(String occSymbol) {
    try {
      Optional<Quote> snapshot = snapshotQuote(occSymbol);
      if (snapshot.isEmpty()) {
        onPollFailure(occSymbol);
        return;
      }
      Quote q = snapshot.get();
      // Feed health and the failure counter reflect CONNECTIVITY, so they are updated for any
      // successful snapshot — a rejected outlier still proves the feed is alive.
      feedHealth.recordTick(FeedHealth.Feed.OPTION);
      optionPollFailures.remove(occSymbol);
      // Stamped for ANY successful snapshot, on the same reasoning as feedHealth above: a quote the
      // guard goes on to reject still proves the feed is alive. This is the stamp /live reads, not
      // the emit stamp — see PremiumFeedStatus for why a tick clock cannot answer the question.
      premiumLiveness.computeIfAbsent(occSymbol, k -> new Liveness()).recordPollOk();
      if (!acceptPremiumQuote(occSymbol, q)) {
        return;
      }
      Tick tick = new Tick(q.occSymbol(), q.mid(), q.bid(), q.ask(), q.retrievedAt());
      List<Consumer<Tick>> listeners = bySymbol.get(occSymbol);
      if (listeners == null) {
        return;
      }
      for (Consumer<Tick> l : listeners) {
        try {
          l.accept(tick);
        } catch (RuntimeException ignored) {
          // One bad listener must not poison the poll.
        }
      }
      premiumLiveness.computeIfAbsent(occSymbol, k -> new Liveness()).recordEmit();
    } catch (RuntimeException e) {
      log.warn("Alpaca premium poll failed for {}: {}", occSymbol, e.getMessage());
      onPollFailure(occSymbol);
    }
  }

  private void onPollFailure(String occSymbol) {
    optionPollFailures.merge(occSymbol, 1, Integer::sum);
    // Whole-feed-dead check: every subscribed OCC must be past the threshold. A healthy OCC clears
    // its own count on success (and recordTick keeps the gauge connected), so this only trips when
    // nothing is ticking — not when one of several contracts has a transient miss.
    if (allSubscribedOccsFailing()) {
      feedHealth.markDisconnected(FeedHealth.Feed.OPTION);
    }
  }

  private boolean allSubscribedOccsFailing() {
    if (bySymbol.isEmpty()) {
      return false;
    }
    for (String occ : bySymbol.keySet()) {
      Integer fails = optionPollFailures.get(occ);
      if (fails == null || fails < OPTION_POLL_FAIL_THRESHOLD) {
        return false;
      }
    }
    return true;
  }

  /**
   * Starts the per-OCC premium poll (once, on the first subscriber, under the per-symbol lock).
   * Package-private so tests can override it to count the lifecycle without real scheduling.
   */
  void startPremiumPoll(String occSymbol) {
    long intervalMs = props.effectivePremiumPollIntervalMs();
    // compute() holds the per-key bin lock, so a concurrent stopPremiumPoll on the same OCC cannot
    // interleave: start and stop are mutually exclusive even though subscribePremium/close() lock
    // different per-symbol-list monitors. Never start a second task if one is already live.
    premiumPolls.compute(
        occSymbol,
        (k, existing) ->
            existing != null
                ? existing
                : scheduler.scheduleAtFixedRate(
                    () -> pollOnce(occSymbol), 0L, intervalMs, TimeUnit.MILLISECONDS));
  }

  /**
   * Cancels the per-OCC premium poll (when the last subscriber leaves, under the per-symbol lock).
   */
  void stopPremiumPoll(String occSymbol) {
    // computeIfPresent holds the same bin lock startPremiumPoll uses, so cancel+remove is atomic
    // against a racing re-subscribe — no lost-cancel and no orphaned task left in the map.
    premiumPolls.computeIfPresent(
        occSymbol,
        (k, task) -> {
          task.cancel(false);
          return null;
        });
    optionPollFailures.remove(occSymbol);
    // Drop the outlier-guard state with the poll, or every closed contract leaks three entries for
    // the worker's lifetime — and a re-subscribe to the same OCC days later would resume against a
    // stale reference price, rejecting the entire live market as an outlier until it corroborated.
    lastAcceptedPremium.remove(occSymbol);
    pendingPremiumCandidate.remove(occSymbol);
    lastQuoteStamp.remove(occSymbol);
    if (premiumPolls.isEmpty()) {
      feedHealth.markDisconnected(FeedHealth.Feed.OPTION);
    }
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

  @PreDestroy
  void shutdown() {
    // shutdownNow cancels the premium-poll tasks (they run on this scheduler) + stock reconnect.
    scheduler.shutdownNow();
    premiumPolls.clear();
    WebSocket stockSocket = stockWs;
    if (stockSocket != null) {
      try {
        stockSocket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
      } catch (RuntimeException ignored) {
        // Best-effort
      }
    }
  }

  @Override
  public Map<String, PremiumFeedStatus> premiumFeedStatus() {
    // Built from bySymbol, not from premiumLiveness: only SUBSCRIBED contracts are reportable. A
    // poll for an unsubscribed OCC (pollOnce is package-private and tests drive it directly) must
    // not conjure a phantom row, and a contract whose subscribers have all left must vanish even if
    // a stamp lingers.
    Map<String, PremiumFeedStatus> out = new LinkedHashMap<>();
    for (Map.Entry<String, List<Consumer<Tick>>> e : bySymbol.entrySet()) {
      String occ = e.getKey();
      Liveness lv = premiumLiveness.get(occ);
      out.put(
          occ,
          new PremiumFeedStatus(
              occ,
              e.getValue().size(),
              lv == null ? 0L : lv.pollOkCount,
              lv == null ? null : lv.lastPollOkAt,
              lv == null ? null : lv.lastEmitAt,
              optionPollFailures.getOrDefault(occ, 0)));
    }
    return out;
  }

  /**
   * Mutable per-OCC liveness counters. Fields are volatile rather than atomic: the poll for one OCC
   * is single-threaded (one scheduled task per contract), so there is no increment race to lose —
   * only a visibility requirement for the HTTP thread that reads them.
   */
  private static final class Liveness {
    private volatile long pollOkCount;
    private volatile Instant lastPollOkAt;
    private volatile Instant lastEmitAt;

    void recordPollOk() {
      pollOkCount++;
      lastPollOkAt = Instant.now();
    }

    void recordEmit() {
      lastEmitAt = Instant.now();
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
          stopPremiumPoll(symbol);
          premiumLiveness.remove(symbol);
        }
      }
    }
  }
}
