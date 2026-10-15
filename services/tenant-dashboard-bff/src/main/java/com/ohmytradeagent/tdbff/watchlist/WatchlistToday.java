package com.ohmytradeagent.tdbff.watchlist;

import com.ohmytradeagent.tdbff.proximity.MarketDataQuoteClient;
import com.ohmytradeagent.tdbff.proximity.MarketDataQuoteClient.EquityBar;
import com.ohmytradeagent.tdbff.watchlist.WatchlistDailyReader.Idea;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Today's watchlist for the /live chain picker: the recorded ideas plus, per idea, whether and when
 * the stock crossed its level ({@link WatchlistCrossing}) and where it is now.
 *
 * <p>Bars are read once per ticker (several ideas share a ticker) and cached for {@link #BARS_TTL}:
 * every viewer opening the picker would otherwise re-read the whole session from Alpaca. A ticker
 * whose bars cannot be read leaves its ideas {@code unknown} — never "waiting", which would claim
 * the stock has not crossed.
 */
@Component
public class WatchlistToday {

  static final ZoneId ET = ZoneId.of("America/New_York");
  private static final LocalTime OPEN = LocalTime.of(9, 30);
  private static final LocalTime CLOSE = LocalTime.of(16, 0);
  private static final Duration BARS_TTL = Duration.ofSeconds(30);

  /** One idea as the picker shows it. {@code crossedAt}/{@code last} are null when unknown. */
  public record IdeaView(
      Idea idea, String status, Instant crossedAt, BigDecimal last, BigDecimal distancePct) {}

  /** {@code ideas} is null when nothing was posted for {@code etDate}. */
  public record Today(LocalDate etDate, List<IdeaView> ideas) {}

  private record CachedBars(Instant readAt, List<EquityBar> bars) {}

  private final WatchlistDailyReader reader;
  private final MarketDataQuoteClient marketData;
  private final Clock clock;
  private final Map<String, CachedBars> barsCache = new ConcurrentHashMap<>();
  // Bounded fan-out: a watchlist is ~5-20 tickers, and each read is one Alpaca call or more.
  private final ExecutorService pool = Executors.newFixedThreadPool(4);

  @Autowired
  public WatchlistToday(WatchlistDailyReader reader, MarketDataQuoteClient marketData) {
    this(reader, marketData, Clock.systemUTC());
  }

  WatchlistToday(WatchlistDailyReader reader, MarketDataQuoteClient marketData, Clock clock) {
    this.reader = reader;
    this.marketData = marketData;
    this.clock = clock;
  }

  @PreDestroy
  void shutdown() {
    pool.shutdownNow();
  }

  /** Throws when the watchlist itself cannot be read (the caller answers 503). */
  public Today today() {
    ZonedDateTime now = clock.instant().atZone(ET);
    LocalDate etDate = now.toLocalDate();
    List<Idea> ideas = reader.ideasFor(etDate);
    if (ideas == null) {
      return new Today(etDate, null);
    }

    Map<String, CompletableFuture<List<EquityBar>>> barsByTicker = new LinkedHashMap<>();
    for (Idea idea : ideas) {
      barsByTicker.computeIfAbsent(
          idea.ticker(), t -> CompletableFuture.supplyAsync(() -> sessionBars(t, now), pool));
    }

    List<IdeaView> out = new ArrayList<>();
    for (Idea idea : ideas) {
      List<EquityBar> bars = barsByTicker.get(idea.ticker()).join();
      out.add(view(idea, bars));
    }
    return new Today(etDate, out);
  }

  static IdeaView view(Idea idea, List<EquityBar> bars) {
    if (bars == null) {
      return new IdeaView(idea, "unknown", null, null, null);
    }
    BigDecimal last = bars.isEmpty() ? null : bars.get(bars.size() - 1).close();
    BigDecimal distance =
        last == null
            ? null
            : last.subtract(idea.level())
                .multiply(BigDecimal.valueOf(100))
                .divide(idea.level(), 2, RoundingMode.HALF_UP);
    Instant crossedAt = WatchlistCrossing.firstCross(bars, idea.direction(), idea.level());
    return new IdeaView(idea, crossedAt == null ? "waiting" : "crossed", crossedAt, last, distance);
  }

  /**
   * Today's regular-session bars so far, or null when they could not be read. Before the open this
   * is an empty list: nothing has crossed yet.
   */
  private List<EquityBar> sessionBars(String ticker, ZonedDateTime now) {
    ZonedDateTime open = now.with(OPEN);
    if (now.isBefore(open)) {
      return List.of();
    }
    CachedBars cached = barsCache.get(ticker);
    Instant nowInstant = now.toInstant();
    if (cached != null
        && cached.readAt().atZone(ET).toLocalDate().equals(now.toLocalDate())
        && cached.readAt().plus(BARS_TTL).isAfter(nowInstant)) {
      return cached.bars();
    }
    ZonedDateTime end = now.isAfter(now.with(CLOSE)) ? now.with(CLOSE) : now;
    List<EquityBar> bars = marketData.equityBars1m(ticker, open.toInstant(), end.toInstant());
    if (bars != null) {
      barsCache.put(ticker, new CachedBars(nowInstant, bars));
    }
    return bars;
  }
}
