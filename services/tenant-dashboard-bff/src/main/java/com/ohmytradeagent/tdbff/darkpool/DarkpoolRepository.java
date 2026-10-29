package com.ohmytradeagent.tdbff.darkpool;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Store for the {@code /dark-pool} data product (V15): off-exchange prints with the NBBO at the
 * print, and per-(symbol, 5-minute) aggregates. Writes the collector's ingest and serves the page's
 * two reads, all through the least-privilege {@code dashboard_writer} DSL — the only dashboard-DB
 * role the BFF has a DSLContext for.
 *
 * <p>DARK by default via a two-name {@code @ConditionalOnProperty}; see {@code application.yml}.
 *
 * <p>Ingest is idempotent: prints dedupe on {@code (symbol, ts, trade_id)} (ON CONFLICT DO
 * NOTHING), bars upsert on {@code (symbol, bucket)} (ON CONFLICT DO UPDATE) because a bucket is
 * re-aggregated and re-posted as it fills. Every timestamp bind carries an explicit {@code
 * ::timestamptz} cast: jOOQ renders an {@code OffsetDateTime} as a STRING for PostgreSQL, and an
 * uncast string against a timestamptz column fails with 42804 (the lesson of {@code
 * OptionsChatRepositoryIT}).
 */
@Repository
@ConditionalOnProperty(
    name = {"darkpool.enabled", "dashboard.writer.enabled"},
    havingValue = "true")
public class DarkpoolRepository {

  /**
   * A print at or above this notional is "large" for the summary's price levels. Phase 2's
   * collector uses the same threshold for the bars' {@code large_count}/{@code large_volume}; keep
   * the two aligned.
   */
  public static final BigDecimal LARGE_PRINT_NOTIONAL = new BigDecimal("200000");

  /** How many price levels the summary reports per symbol. */
  static final int LEVELS_PER_SYMBOL = 3;

  /**
   * Rows per multi-row statement. The writer pool has TWO connections shared with /status and
   * /live, so a 5000-row batch must not be 5000 round trips holding one of them; 500 rows is well
   * under PostgreSQL's 65535-parameter cap at 13 binds per row.
   */
  static final int CHUNK = 500;

  private static final ZoneId ET = ZoneId.of("America/New_York");

  private final DSLContext writerDsl;

  public DarkpoolRepository(@Qualifier("dashboardWriterDsl") DSLContext writerDsl) {
    this.writerDsl = writerDsl;
  }

  /**
   * One off-exchange print as posted by the collector and as served to the page. Wire names are
   * snake_case, identical to the V15 columns and the GET responses.
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Print(
      Long tradeId,
      String symbol,
      OffsetDateTime ts,
      BigDecimal price,
      Integer size,
      BigDecimal notional,
      String exchange,
      String conditions,
      String tape,
      BigDecimal bid,
      BigDecimal ask,
      OffsetDateTime quoteTs,
      String side) {}

  /** One (symbol, 5-minute bucket) aggregate as posted by the collector; snake_case on the wire. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Bar(
      String symbol,
      OffsetDateTime bucket,
      Long dpVolume,
      Integer dpTrades,
      Long totalVolume,
      Integer totalTrades,
      Long buyVolume,
      Long sellVolume,
      Integer largeCount,
      Long largeVolume,
      BigDecimal dpVwap) {}

  /** How many rows an ingest actually wrote (a replayed print counts 0; an upserted bar 1). */
  public record IngestResult(int storedPrints, int storedBars) {}

  /** One price level: the notional-weighted price of a 0.1% bucket of large prints. */
  public record Level(BigDecimal price, BigDecimal notional, int prints) {}

  /**
   * One symbol's session summary. Ratios are {@code null} (never 0, never NaN) when there is
   * nothing to divide by.
   */
  public record SymbolSummary(
      String symbol,
      long dpVolume,
      long totalVolume,
      Double dpShare,
      long buyVolume,
      long sellVolume,
      Double buyRatio,
      Double lateBuyRatio,
      int largeCount,
      long largeVolume,
      List<Level> levels) {}

  /**
   * Store a batch. ONE transaction, so a mid-batch failure cannot leave a bucket's bars without
   * their prints; multi-row statements in chunks of {@value #CHUNK}, so a batch costs a handful of
   * round trips rather than one per row. The caller has already validated every row ({@link
   * DarkpoolIngestBatch}).
   */
  public IngestResult ingest(List<Print> prints, List<Bar> bars) {
    if (prints.isEmpty() && bars.isEmpty()) {
      return new IngestResult(0, 0);
    }
    return writerDsl.transactionResult(
        cfg -> {
          DSLContext tx = DSL.using(cfg);
          int storedPrints = 0;
          for (int from = 0; from < prints.size(); from += CHUNK) {
            storedPrints +=
                insertPrints(tx, prints.subList(from, Math.min(from + CHUNK, prints.size())));
          }
          // A multi-row ON CONFLICT DO UPDATE refuses to touch the same row twice in one
          // statement, so a batch that carries one bucket twice (the collector re-aggregating it
          // within a poll) is collapsed to its LAST copy before chunking.
          List<Bar> distinct = new ArrayList<>(lastPerBucket(bars).values());
          int storedBars = 0;
          for (int from = 0; from < distinct.size(); from += CHUNK) {
            storedBars +=
                upsertBars(tx, distinct.subList(from, Math.min(from + CHUNK, distinct.size())));
          }
          return new IngestResult(storedPrints, storedBars);
        });
  }

  /** One multi-row INSERT; returns how many rows were new (ON CONFLICT DO NOTHING counts 0). */
  private static int insertPrints(DSLContext tx, List<Print> chunk) {
    List<Object> binds = new ArrayList<>(chunk.size() * 13);
    for (Print p : chunk) {
      binds.add(p.tradeId());
      binds.add(p.symbol());
      binds.add(p.ts());
      binds.add(p.price());
      binds.add(p.size());
      binds.add(p.notional());
      binds.add(p.exchange());
      binds.add(p.conditions());
      binds.add(p.tape());
      binds.add(p.bid());
      binds.add(p.ask());
      binds.add(p.quoteTs());
      binds.add(p.side());
    }
    // ON CONFLICT DO NOTHING reads the arbiter columns, which is why V15 grants SELECT.
    return tx.execute(
        "INSERT INTO darkpool_print (trade_id, symbol, ts, price, size, notional, exchange,"
            + " conditions, tape, bid, ask, quote_ts, side) VALUES "
            + tuples(
                chunk.size(), "(?, ?, ?::timestamptz, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?)")
            + " ON CONFLICT (symbol, ts, trade_id) DO NOTHING",
        binds.toArray());
  }

  /** One multi-row upsert; every row is inserted or updated, so the count is the chunk size. */
  private static int upsertBars(DSLContext tx, List<Bar> chunk) {
    List<Object> binds = new ArrayList<>(chunk.size() * 11);
    for (Bar b : chunk) {
      binds.add(b.symbol());
      binds.add(b.bucket());
      binds.add(b.dpVolume());
      binds.add(b.dpTrades());
      binds.add(b.totalVolume());
      binds.add(b.totalTrades());
      binds.add(b.buyVolume());
      binds.add(b.sellVolume());
      binds.add(b.largeCount());
      binds.add(b.largeVolume());
      binds.add(b.dpVwap());
    }
    return tx.execute(
        "INSERT INTO darkpool_bar_5m (symbol, bucket, dp_volume, dp_trades, total_volume,"
            + " total_trades, buy_volume, sell_volume, large_count, large_volume, dp_vwap) VALUES "
            + tuples(chunk.size(), "(?, ?::timestamptz, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
            + " ON CONFLICT (symbol, bucket) DO UPDATE SET"
            + " dp_volume = EXCLUDED.dp_volume, dp_trades = EXCLUDED.dp_trades,"
            + " total_volume = EXCLUDED.total_volume, total_trades = EXCLUDED.total_trades,"
            + " buy_volume = EXCLUDED.buy_volume, sell_volume = EXCLUDED.sell_volume,"
            + " large_count = EXCLUDED.large_count, large_volume = EXCLUDED.large_volume,"
            + " dp_vwap = EXCLUDED.dp_vwap",
        binds.toArray());
  }

  private static Map<String, Bar> lastPerBucket(List<Bar> bars) {
    Map<String, Bar> out = new LinkedHashMap<>();
    for (Bar b : bars) {
      out.put(b.symbol() + "@" + b.bucket().toInstant(), b);
    }
    return out;
  }

  private static String tuples(int n, String tuple) {
    return String.join(", ", Collections.nCopies(n, tuple));
  }

  /**
   * Newest-first prints, optionally one symbol, optionally since an instant (inclusive), at or
   * above {@code minNotional}. Uses {@code (symbol, ts DESC)} when a symbol is given and {@code (ts
   * DESC)} otherwise — the two indexes V15 creates.
   */
  public List<Print> recentPrints(
      String symbol, OffsetDateTime since, BigDecimal minNotional, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT trade_id, symbol, ts, price, size, notional, exchange, conditions, tape, bid,"
                + " ask, quote_ts, side FROM darkpool_print WHERE notional >= ?");
    List<Object> binds = new ArrayList<>();
    binds.add(minNotional);
    if (symbol != null) {
      sql.append(" AND symbol = ?");
      binds.add(symbol);
    }
    if (since != null) {
      sql.append(" AND ts >= ?::timestamptz");
      binds.add(since);
    }
    sql.append(" ORDER BY ts DESC, trade_id DESC LIMIT ?");
    binds.add(limit);

    Result<Record> rows = writerDsl.fetch(sql.toString(), binds.toArray());
    List<Print> out = new ArrayList<>(rows.size());
    for (Record r : rows) {
      out.add(
          new Print(
              r.get("trade_id", Long.class),
              r.get("symbol", String.class),
              r.get("ts", OffsetDateTime.class),
              r.get("price", BigDecimal.class),
              r.get("size", Integer.class),
              r.get("notional", BigDecimal.class),
              r.get("exchange", String.class),
              r.get("conditions", String.class),
              r.get("tape", String.class),
              r.get("bid", BigDecimal.class),
              r.get("ask", BigDecimal.class),
              r.get("quote_ts", OffsetDateTime.class),
              r.get("side", String.class)));
    }
    return out;
  }

  /**
   * Per-symbol summary of one ET session date: the bars' sums and ratios, the late-session buy
   * ratio, and the top {@value #LEVELS_PER_SYMBOL} price levels by large-print notional, prices
   * bucketed to 0.1%.
   *
   * <p>REGULAR TRADING HOURS ONLY, for every metric: bars whose bucket starts in [09:30, 16:00) ET
   * and prints with {@code ts} in the same window. The late window is buckets starting in [14:00,
   * 15:30) ET, half-open like the session. Bounds are computed here as instants, so DST is handled
   * by {@code java.time} rather than by SQL timezone arithmetic. Ordered by symbol.
   */
  public List<SymbolSummary> summary(LocalDate sessionDateEt) {
    OffsetDateTime start = sessionDateEt.atTime(9, 30).atZone(ET).toOffsetDateTime();
    OffsetDateTime end = sessionDateEt.atTime(16, 0).atZone(ET).toOffsetDateTime();
    OffsetDateTime lateStart = sessionDateEt.atTime(14, 0).atZone(ET).toOffsetDateTime();
    OffsetDateTime lateEnd = sessionDateEt.atTime(15, 30).atZone(ET).toOffsetDateTime();

    Map<String, List<Level>> levels = levels(start, end);

    Result<Record> rows =
        writerDsl.fetch(
            "SELECT symbol, sum(dp_volume) AS dp_volume, sum(total_volume) AS total_volume,"
                + " sum(buy_volume) AS buy_volume, sum(sell_volume) AS sell_volume,"
                + " sum(buy_volume) FILTER (WHERE bucket >= ?::timestamptz"
                + " AND bucket < ?::timestamptz) AS late_buy_volume,"
                + " sum(sell_volume) FILTER (WHERE bucket >= ?::timestamptz"
                + " AND bucket < ?::timestamptz) AS late_sell_volume,"
                + " sum(large_count) AS large_count, sum(large_volume) AS large_volume"
                + " FROM darkpool_bar_5m WHERE bucket >= ?::timestamptz AND bucket < ?::timestamptz"
                + " GROUP BY symbol",
            lateStart,
            lateEnd,
            lateStart,
            lateEnd,
            start,
            end);

    // TreeMap: symbols come back sorted whether they arrived from the bars or only from prints.
    Map<String, SymbolSummary> bySymbol = new TreeMap<>();
    for (Record r : rows) {
      String symbol = r.get("symbol", String.class);
      long dp = sumOrZero(r, "dp_volume");
      long total = sumOrZero(r, "total_volume");
      long buy = sumOrZero(r, "buy_volume");
      long sell = sumOrZero(r, "sell_volume");
      long lateBuy = sumOrZero(r, "late_buy_volume");
      long lateSell = sumOrZero(r, "late_sell_volume");
      bySymbol.put(
          symbol,
          new SymbolSummary(
              symbol,
              dp,
              total,
              ratio(dp, total),
              buy,
              sell,
              ratio(buy, buy + sell),
              ratio(lateBuy, lateBuy + lateSell),
              (int) sumOrZero(r, "large_count"),
              sumOrZero(r, "large_volume"),
              levels.getOrDefault(symbol, List.of())));
    }
    // A symbol with large prints but no bars yet (the collector posts prints as they happen and
    // the bar at bucket close) still gets its levels.
    for (Map.Entry<String, List<Level>> e : levels.entrySet()) {
      bySymbol.putIfAbsent(
          e.getKey(),
          new SymbolSummary(e.getKey(), 0, 0, null, 0, 0, null, null, 0, 0, e.getValue()));
    }
    return new ArrayList<>(bySymbol.values());
  }

  /**
   * Top price levels per symbol over [start, end). A level is a 0.1% price bucket, {@code
   * floor(ln(price) / ln(1.001))}, so neighbouring prices cluster without a reference price; the
   * reported price is the bucket's notional-weighted mean.
   */
  private Map<String, List<Level>> levels(OffsetDateTime start, OffsetDateTime end) {
    Result<Record> rows =
        writerDsl.fetch(
            "SELECT symbol, price, notional, prints FROM ("
                + " SELECT symbol, round(sum(price * notional) / sum(notional), 2) AS price,"
                + " sum(notional) AS notional, count(*) AS prints,"
                + " row_number() OVER (PARTITION BY symbol ORDER BY sum(notional) DESC) AS rn"
                + " FROM darkpool_print"
                + " WHERE ts >= ?::timestamptz AND ts < ?::timestamptz AND notional >= ?"
                + " AND price > 0"
                + " GROUP BY symbol, floor(ln(price) / ln(1.001))"
                + ") t WHERE rn <= ? ORDER BY symbol, notional DESC",
            start,
            end,
            LARGE_PRINT_NOTIONAL,
            LEVELS_PER_SYMBOL);
    Map<String, List<Level>> out = new TreeMap<>();
    for (Record r : rows) {
      out.computeIfAbsent(r.get("symbol", String.class), k -> new ArrayList<>())
          .add(
              new Level(
                  r.get("price", BigDecimal.class),
                  r.get("notional", BigDecimal.class),
                  r.get("prints", Integer.class)));
    }
    return out;
  }

  private static long sumOrZero(Record r, String column) {
    Long v = r.get(column, Long.class);
    return v == null ? 0 : v;
  }

  private static Double ratio(long numerator, long denominator) {
    return denominator == 0 ? null : (double) numerator / denominator;
  }

  /**
   * Delete up to {@code batchLimit} prints before {@code cutoff}, oldest first. Bounded, like the
   * options-chat sweep: one enormous first-run DELETE would hold row locks and inflate WAL on the
   * volume the trading databases share.
   */
  public int deletePrintsOlderThan(OffsetDateTime cutoff, int batchLimit) {
    if (batchLimit <= 0) {
      return 0;
    }
    return writerDsl.execute(
        "DELETE FROM darkpool_print WHERE id IN ("
            + " SELECT id FROM darkpool_print WHERE ts < ?::timestamptz ORDER BY ts ASC LIMIT ?)",
        cutoff,
        batchLimit);
  }

  /** Delete up to {@code batchLimit} bars whose bucket starts before {@code cutoff}. */
  public int deleteBarsOlderThan(OffsetDateTime cutoff, int batchLimit) {
    if (batchLimit <= 0) {
      return 0;
    }
    return writerDsl.execute(
        "DELETE FROM darkpool_bar_5m WHERE (symbol, bucket) IN ("
            + " SELECT symbol, bucket FROM darkpool_bar_5m WHERE bucket < ?::timestamptz"
            + " ORDER BY bucket ASC LIMIT ?)",
        cutoff,
        batchLimit);
  }
}
