package com.ohmytradeagent.marketdata.marks;

import com.ohmytradeagent.marketdata.health.FeedHealth;
import com.ohmytradeagent.marketdata.marks.DisplayInterestRegistry.Admission;
import com.ohmytradeagent.marketdata.provider.MarketDataProvider;
import com.ohmytradeagent.marketdata.provider.Quote;
import com.ohmytradeagent.marketdata.provider.Tick;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Display-only option marks for the /live holdings table (PLAN-2026-10-01 P1).
 *
 * <p><b>Isolated from trail liveness by construction.</b> This class never calls {@code
 * subscribePremium}, never touches the provider's poll / liveness / FeedHealth state, and owns its
 * own scheduler. Per watched OCC it runs one fixed-rate task at the premium poll interval that
 * either copies the trail poll's cached quote ({@link MarketDataProvider#lastPolledQuote}, no
 * request) when a trail poll is active, or calls {@link MarketDataProvider#snapshotQuote} — the
 * one-shot REST read that records nothing. A task cancels itself {@link
 * DisplayInterestRegistry#TTL} after the last request, so polling only runs while someone is
 * watching.
 *
 * <p>Underlying: the last SIP trade already received (passive, no subscription) when the equity
 * feed is connected and the trade is at most {@link #SIP_MAX_AGE} old; otherwise a stock snapshot
 * cached per ticker and refreshed at most once per {@link #SNAPSHOT_MIN_GAP}.
 */
@Component
public class DisplayMarksService {

  private static final Logger log = LoggerFactory.getLogger(DisplayMarksService.class);
  static final Duration SIP_MAX_AGE = Duration.ofSeconds(2);
  static final Duration SNAPSHOT_MIN_GAP = Duration.ofSeconds(1);

  /** OCC suffix after the root: yymmdd + C/P + 8-digit strike. */
  private static final int OCC_SUFFIX_LEN = 15;

  public record Underlying(String ticker, BigDecimal price, Instant at) {}

  /** {@code quote} is null until the first poll lands (then {@code warming}) or when capped. */
  public record DisplayMark(
      String occ, Quote quote, Underlying underlying, boolean warming, boolean capped) {}

  private final MarketDataProvider provider;
  private final FeedHealth feedHealth;
  private final Clock clock;
  private final long intervalMs;
  private final ScheduledExecutorService scheduler;

  private final Object lock = new Object();
  private final DisplayInterestRegistry registry;
  private final Map<String, ScheduledFuture<?>> tasks = new HashMap<>(); // guarded by lock
  private final ConcurrentHashMap<String, Quote> quotes = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Underlying> snapshots = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Instant> snapshotFetchedAt = new ConcurrentHashMap<>();

  @Autowired
  public DisplayMarksService(
      MarketDataProvider provider,
      FeedHealth feedHealth,
      @Value("${market-data.alpaca.premium-poll-interval-ms:500}") long intervalMs) {
    this(provider, feedHealth, Clock.systemUTC(), intervalMs, defaultScheduler());
  }

  /**
   * Visible for tests: injected clock + scheduler (the isolation test lives beside the provider).
   */
  public DisplayMarksService(
      MarketDataProvider provider,
      FeedHealth feedHealth,
      Clock clock,
      long intervalMs,
      ScheduledExecutorService scheduler) {
    this.provider = provider;
    this.feedHealth = feedHealth;
    this.clock = clock;
    // Same effective cadence as the trail poll (AlpacaMarketDataProperties: <=0 -> 500ms).
    this.intervalMs = intervalMs > 0 ? intervalMs : 500L;
    this.scheduler = scheduler;
    this.registry = new DisplayInterestRegistry(clock);
  }

  private static ScheduledExecutorService defaultScheduler() {
    // Own pool, never the provider's: a slow display snapshot must not delay a trail poll.
    ScheduledThreadPoolExecutor pool =
        new ScheduledThreadPoolExecutor(
            4,
            r -> {
              Thread t = new Thread(r, "display-marks-poll");
              t.setDaemon(true);
              return t;
            });
    pool.setRemoveOnCancelPolicy(true);
    return pool;
  }

  public Instant now() {
    return clock.instant();
  }

  /** Registers/refreshes display interest for each OCC and returns the cached marks, in order. */
  public List<DisplayMark> marks(List<String> occs) {
    List<DisplayMark> out = new ArrayList<>(occs.size());
    for (String occ : occs) {
      Admission admission;
      synchronized (lock) {
        admission = registry.touch(occ);
        if (admission == Admission.NEW) {
          tasks.put(
              occ,
              scheduler.scheduleAtFixedRate(
                  () -> pollOnce(occ), 0L, intervalMs, TimeUnit.MILLISECONDS));
        }
      }
      String ticker = tickerOf(occ);
      if (admission == Admission.CAPPED) {
        out.add(new DisplayMark(occ, null, new Underlying(ticker, null, null), false, true));
        continue;
      }
      Quote q = quotes.get(occ);
      out.add(new DisplayMark(occ, q, underlying(ticker), q == null, false));
    }
    return out;
  }

  private void pollOnce(String occ) {
    try {
      synchronized (lock) {
        if (registry.expireIfIdle(occ)) {
          ScheduledFuture<?> task = tasks.remove(occ);
          if (task != null) {
            task.cancel(false);
          }
          quotes.remove(occ);
          return;
        }
      }
      Optional<Quote> q =
          provider.premiumPollActive(occ)
              ? provider.lastPolledQuote(occ)
              : provider.snapshotQuote(occ);
      q.ifPresent(v -> quotes.put(occ, v));
      refreshUnderlying(tickerOf(occ));
    } catch (RuntimeException e) {
      // Fail-soft: a thrown task would be silently descheduled by the executor.
      log.warn("display marks poll failed for {}: {}", occ, e.getMessage());
    }
  }

  private Underlying underlying(String ticker) {
    if (!isPlainTicker(ticker)) {
      return new Underlying(ticker, null, null);
    }
    Optional<Tick> sip = freshSipTick(ticker);
    if (sip.isPresent()) {
      Tick t = sip.get();
      return new Underlying(ticker, t.premium(), t.retrievedAt().toInstant());
    }
    return snapshots.getOrDefault(ticker, new Underlying(ticker, null, null));
  }

  private void refreshUnderlying(String ticker) {
    if (!isPlainTicker(ticker) || freshSipTick(ticker).isPresent()) {
      return;
    }
    Instant now = clock.instant();
    boolean[] due = {false};
    snapshotFetchedAt.compute(
        ticker,
        (k, prev) -> {
          if (prev == null || !now.isBefore(prev.plus(SNAPSHOT_MIN_GAP))) {
            due[0] = true;
            return now;
          }
          return prev;
        });
    if (due[0]) {
      provider
          .snapshotEquityPrice(ticker)
          .ifPresent(p -> snapshots.put(ticker, new Underlying(ticker, p, now)));
    }
  }

  private Optional<Tick> freshSipTick(String ticker) {
    if (!feedHealth.connected(FeedHealth.Feed.EQUITY)) {
      return Optional.empty();
    }
    Instant oldest = clock.instant().minus(SIP_MAX_AGE);
    return provider
        .lastEquityTick(ticker)
        .filter(t -> t.retrievedAt() != null && !t.retrievedAt().toInstant().isBefore(oldest));
  }

  /** Root of a compact or space-padded OCC ({@code NVDA260516C00140000} -> {@code NVDA}). */
  static String tickerOf(String occ) {
    String compact = occ.replace(" ", "");
    return compact.length() > OCC_SUFFIX_LEN
        ? compact.substring(0, compact.length() - OCC_SUFFIX_LEN)
        : null;
  }

  /** Adjusted roots (e.g. {@code TSLA1}) are not stock tickers: no underlying lookup. */
  private static boolean isPlainTicker(String ticker) {
    return ticker != null && ticker.chars().allMatch(Character::isLetter);
  }

  @PreDestroy
  void shutdown() {
    scheduler.shutdownNow();
  }
}
