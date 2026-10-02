package com.ohmytradeagent.marketdata.marks;

import com.ohmytradeagent.marketdata.health.FeedHealth;
import com.ohmytradeagent.marketdata.marks.DisplayInterestRegistry.Admission;
import com.ohmytradeagent.marketdata.provider.MarketDataProvider;
import com.ohmytradeagent.marketdata.provider.PremiumFeedStatus;
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
 * <p>{@code polledAt}: when the cached quote was last confirmed by a successful poll — the display
 * snapshot's own success time, or on reuse the trail poll's {@code lastPollOkAt} (read-only via
 * {@link MarketDataProvider#premiumFeedStatus}). A failed or skipped poll never advances it.
 *
 * <p>Underlying: the last SIP trade already received (passive, no subscription) when the equity
 * feed is connected and the trade is at most {@link #SIP_MAX_AGE} old; otherwise a stock snapshot
 * cached per ticker and refreshed at most once per {@link #SNAPSHOT_MIN_GAP}, stamped with the
 * trade's own time.
 *
 * <p>Every display REST call (option or stock snapshot) draws from one {@link
 * DisplayRequestBudget}; without a permit the run is skipped and the cached value kept.
 */
@Component
public class DisplayMarksService {

  private static final Logger log = LoggerFactory.getLogger(DisplayMarksService.class);
  static final Duration SIP_MAX_AGE = Duration.ofSeconds(2);
  static final Duration SNAPSHOT_MIN_GAP = Duration.ofSeconds(1);

  /** OCC suffix after the root: yymmdd + C/P + 8-digit strike. */
  private static final int OCC_SUFFIX_LEN = 15;

  public record Underlying(String ticker, BigDecimal price, Instant at) {}

  /**
   * {@code quote} is null until the first poll lands (then {@code warming}) or when capped. {@code
   * polledAt} is the last successful poll of that quote; null when unknown.
   */
  public record DisplayMark(
      String occ,
      Quote quote,
      Instant polledAt,
      Underlying underlying,
      boolean warming,
      boolean capped) {}

  private record Cached(Quote quote, Instant polledAt) {}

  private final MarketDataProvider provider;
  private final FeedHealth feedHealth;
  private final Clock clock;
  private final long intervalMs;
  private final ScheduledExecutorService scheduler;

  private final Object lock = new Object();
  private final DisplayInterestRegistry registry;
  private final DisplayRequestBudget budget;
  private final Map<String, ScheduledFuture<?>> tasks = new HashMap<>(); // guarded by lock
  private final ConcurrentHashMap<String, Cached> quotes = new ConcurrentHashMap<>();
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
    this.budget = new DisplayRequestBudget(clock);
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
          try {
            tasks.put(
                occ,
                scheduler.scheduleAtFixedRate(
                    () -> pollOnce(occ), 0L, intervalMs, TimeUnit.MILLISECONDS));
          } catch (RuntimeException e) {
            // e.g. RejectedExecutionException on a shutdown race: never keep a slot with no task.
            registry.forget(occ);
            log.warn("display marks scheduling failed for {}: {}", occ, e.getMessage());
          }
        }
      }
      String ticker = tickerOf(occ);
      if (admission == Admission.CAPPED) {
        out.add(new DisplayMark(occ, null, null, new Underlying(ticker, null, null), false, true));
        continue;
      }
      Cached c = quotes.get(occ);
      out.add(
          c == null
              ? new DisplayMark(occ, null, null, underlying(ticker), true, false)
              : new DisplayMark(occ, c.quote(), c.polledAt(), underlying(ticker), false, false));
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
      // Reusing the trail poll's quote costs no permit; a REST snapshot needs one (else keep the
      // previous cached quote).
      if (provider.premiumPollActive(occ)) {
        // Two separate reads of the trail's state: the pair can be one trail poll (~500ms) apart.
        Instant trailOkAt = trailLastPollOkAt(occ);
        provider.lastPolledQuote(occ).ifPresent(v -> quotes.put(occ, new Cached(v, trailOkAt)));
      } else if (budget.tryAcquire()) {
        provider.snapshotQuote(occ).ifPresent(v -> quotes.put(occ, new Cached(v, clock.instant())));
      }
      refreshUnderlying(tickerOf(occ));
    } catch (RuntimeException e) {
      // Fail-soft: a thrown task would be silently descheduled by the executor.
      log.warn("display marks poll failed for {}: {}", occ, e.getMessage());
    } catch (Error e) {
      // The executor deschedules the task on rethrow; free the slot so it cannot leak, and let the
      // next request re-register a fresh task.
      log.error("display marks poll died for {}; releasing its slot", occ, e);
      synchronized (lock) {
        registry.forget(occ);
        ScheduledFuture<?> task = tasks.remove(occ);
        if (task != null) {
          task.cancel(false);
        }
      }
      quotes.remove(occ);
      throw e;
    }
  }

  /** The trail poll's last-success stamp for {@code occ} (padded keys matched compact), or null. */
  private Instant trailLastPollOkAt(String occ) {
    String compact = occ.replace(" ", "");
    return provider.premiumFeedStatus().values().stream()
        .filter(st -> st.occSymbol().replace(" ", "").equals(compact))
        .map(PremiumFeedStatus::lastPollOkAt)
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(null);
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
          if ((prev == null || !now.isBefore(prev.plus(SNAPSHOT_MIN_GAP))) && budget.tryAcquire()) {
            due[0] = true;
            return now;
          }
          return prev;
        });
    if (due[0]) {
      // Stamp with the trade's own time, so an old print never reads as fresh.
      provider
          .snapshotEquityTrade(ticker)
          .ifPresent(
              t ->
                  snapshots.put(
                      ticker, new Underlying(ticker, t.premium(), t.retrievedAt().toInstant())));
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
