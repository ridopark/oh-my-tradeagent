package com.ohmytradeagent.tdbff.flow;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Store for the {@code /flow} data product (V16): option-flow orders and per-ticker daily
 * summaries. Writes the collector's ingest and serves the page's two reads, all through the
 * least-privilege {@code dashboard_writer} DSL.
 *
 * <p>DARK by default via a two-name {@code @ConditionalOnProperty}; see {@code application.yml}.
 *
 * <p>Ingest is idempotent: orders dedupe on {@code order_id} (ON CONFLICT DO NOTHING — an order is
 * immutable), ticker-days upsert on {@code (ticker, session_date)} (ON CONFLICT DO UPDATE) because
 * a day is re-posted as it fills. Every timestamp bind carries an explicit {@code ::timestamptz}
 * cast: jOOQ renders an {@code OffsetDateTime} as a STRING for PostgreSQL, and an uncast string
 * against a timestamptz column fails with 42804 (the lesson of {@code OptionsChatRepositoryIT}).
 */
@Repository
@ConditionalOnProperty(
    name = {"flow.enabled", "dashboard.writer.enabled"},
    havingValue = "true")
public class FlowRepository {

  /**
   * Rows per multi-row statement. The writer pool has TWO connections shared with other routes; a
   * 5000-row batch must not be 5000 round trips holding one of them.
   */
  static final int CHUNK = 500;

  private static final ZoneId ET = ZoneId.of("America/New_York");

  /** Session window in ET: [09:25, 16:20). Used for the date filter on orders. */
  private static final int SESSION_START_HOUR = 9;

  private static final int SESSION_START_MIN = 25;
  private static final int SESSION_END_HOUR = 16;
  private static final int SESSION_END_MIN = 20;

  private final DSLContext writerDsl;

  public FlowRepository(@Qualifier("dashboardWriterDsl") DSLContext writerDsl) {
    this.writerDsl = writerDsl;
  }

  // ---------------------------------------------------------------------------
  // Wire types
  // ---------------------------------------------------------------------------

  /**
   * One option order as posted by the collector and served to the page. Wire names are snake_case,
   * identical to the V16 columns and the GET responses (the plan's binding wire contract).
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Order(
      String orderId,
      String ticker,
      String contract,
      LocalDate expiry,
      BigDecimal strike,
      String right,
      OffsetDateTime firstTs,
      OffsetDateTime lastTs,
      Integer tradeCount,
      Integer exchangeCount,
      Integer size,
      BigDecimal premium,
      BigDecimal vwap,
      String side,
      String sideSource,
      BigDecimal bid,
      BigDecimal ask,
      OffsetDateTime quoteTs,
      Boolean sweep,
      Boolean multiLeg,
      Boolean auction,
      Boolean cross,
      Boolean block,
      Boolean unusual,
      Long dayVolume,
      Integer openInterest,
      BigDecimal underlyingPrice,
      BigDecimal moneynessPct,
      Integer dte,
      String conditions,
      BigDecimal blockPremiumThreshold,
      Integer blockSizeThreshold,
      Integer orderWindowMs,
      Integer quoteMaxAgeMs) {}

  /** One (ticker, session_date) daily summary as posted by the collector; snake_case on wire. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record TickerDay(
      String ticker,
      LocalDate sessionDate,
      BigDecimal callPremium,
      BigDecimal putPremium,
      BigDecimal boughtPremium,
      BigDecimal soldPremium,
      BigDecimal midPremium,
      BigDecimal unknownPremium,
      Integer tradeCount,
      Integer orderCount,
      BigDecimal quotedPremiumShare) {}

  /** How many rows an ingest actually wrote. */
  public record IngestResult(int storedOrders, int storedTickerDays) {}

  /** Filters for the orders read. */
  public record OrderQuery(
      LocalDate date,
      String ticker,
      BigDecimal minPremium,
      Set<String> labels,
      String right,
      String side,
      Integer dteMin,
      Integer dteMax,
      OffsetDateTime before,
      int limit) {}

  // ---------------------------------------------------------------------------
  // Ingest
  // ---------------------------------------------------------------------------

  /**
   * Store a batch. ONE transaction, so a mid-batch failure cannot leave partial data; multi-row
   * statements in chunks of {@value #CHUNK}, so a batch costs a handful of round trips rather than
   * one per row. The caller has already validated every row ({@link FlowIngestBatch}).
   */
  public IngestResult ingest(List<Order> orders, List<TickerDay> tickerDays) {
    if (orders.isEmpty() && tickerDays.isEmpty()) {
      return new IngestResult(0, 0);
    }
    return writerDsl.transactionResult(
        cfg -> {
          DSLContext tx = DSL.using(cfg);
          int storedOrders = 0;
          for (int from = 0; from < orders.size(); from += CHUNK) {
            storedOrders +=
                insertOrders(tx, orders.subList(from, Math.min(from + CHUNK, orders.size())));
          }
          // A multi-row ON CONFLICT DO UPDATE refuses the same PK twice in one statement.
          // Collapse to last per (ticker, session_date) before chunking.
          List<TickerDay> distinct = new ArrayList<>(lastPerDay(tickerDays).values());
          int storedDays = 0;
          for (int from = 0; from < distinct.size(); from += CHUNK) {
            storedDays +=
                upsertTickerDays(
                    tx, distinct.subList(from, Math.min(from + CHUNK, distinct.size())));
          }
          return new IngestResult(storedOrders, storedDays);
        });
  }

  /** One multi-row INSERT; returns how many rows were new (ON CONFLICT DO NOTHING counts 0). */
  private static int insertOrders(DSLContext tx, List<Order> chunk) {
    List<Object> binds = new ArrayList<>(chunk.size() * 34);
    for (Order o : chunk) {
      binds.add(o.orderId());
      binds.add(o.ticker());
      binds.add(o.contract());
      binds.add(o.expiry());
      binds.add(o.strike());
      binds.add(o.right());
      binds.add(o.firstTs());
      binds.add(o.lastTs());
      binds.add(o.tradeCount());
      binds.add(o.exchangeCount());
      binds.add(o.size());
      binds.add(o.premium());
      binds.add(o.vwap());
      binds.add(o.side());
      binds.add(o.sideSource());
      binds.add(o.bid());
      binds.add(o.ask());
      binds.add(o.quoteTs());
      binds.add(o.sweep());
      binds.add(o.multiLeg());
      binds.add(o.auction());
      binds.add(o.cross());
      binds.add(o.block());
      binds.add(o.unusual());
      binds.add(o.dayVolume());
      binds.add(o.openInterest());
      binds.add(o.underlyingPrice());
      binds.add(o.moneynessPct());
      binds.add(o.dte());
      binds.add(o.conditions());
      binds.add(o.blockPremiumThreshold());
      binds.add(o.blockSizeThreshold());
      binds.add(o.orderWindowMs());
      binds.add(o.quoteMaxAgeMs());
    }
    // ON CONFLICT DO NOTHING reads the arbiter column (order_id), which is why V16 grants SELECT.
    return tx.execute(
        "INSERT INTO flow_order (order_id, ticker, contract, expiry, strike, right, first_ts,"
            + " last_ts, trade_count, exchange_count, size, premium, vwap, side, side_source,"
            + " bid, ask, quote_ts, sweep, multi_leg, auction, cross, block, unusual,"
            + " day_volume, open_interest, underlying_price, moneyness_pct, dte, conditions,"
            + " block_premium_threshold, block_size_threshold, order_window_ms, quote_max_age_ms)"
            + " VALUES "
            + tuples(
                chunk.size(),
                "(?, ?, ?, ?::date, ?, ?, ?::timestamptz, ?::timestamptz, ?, ?, ?, ?, ?, ?, ?,"
                    + " ?, ?, ?::timestamptz, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
            + " ON CONFLICT (order_id) DO NOTHING",
        binds.toArray());
  }

  /** One multi-row upsert for ticker-days; every row is inserted or updated. */
  private static int upsertTickerDays(DSLContext tx, List<TickerDay> chunk) {
    List<Object> binds = new ArrayList<>(chunk.size() * 11);
    for (TickerDay t : chunk) {
      binds.add(t.ticker());
      binds.add(t.sessionDate());
      binds.add(t.callPremium());
      binds.add(t.putPremium());
      binds.add(t.boughtPremium());
      binds.add(t.soldPremium());
      binds.add(t.midPremium());
      binds.add(t.unknownPremium());
      binds.add(t.tradeCount());
      binds.add(t.orderCount());
      binds.add(t.quotedPremiumShare());
    }
    return tx.execute(
        "INSERT INTO flow_ticker_day (ticker, session_date, call_premium, put_premium,"
            + " bought_premium, sold_premium, mid_premium, unknown_premium, trade_count,"
            + " order_count, quoted_premium_share) VALUES "
            + tuples(chunk.size(), "(?, ?::date, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
            + " ON CONFLICT (ticker, session_date) DO UPDATE SET"
            + " call_premium = EXCLUDED.call_premium, put_premium = EXCLUDED.put_premium,"
            + " bought_premium = EXCLUDED.bought_premium, sold_premium = EXCLUDED.sold_premium,"
            + " mid_premium = EXCLUDED.mid_premium, unknown_premium = EXCLUDED.unknown_premium,"
            + " trade_count = EXCLUDED.trade_count, order_count = EXCLUDED.order_count,"
            + " quoted_premium_share = EXCLUDED.quoted_premium_share",
        binds.toArray());
  }

  private static java.util.Map<String, TickerDay> lastPerDay(List<TickerDay> days) {
    java.util.Map<String, TickerDay> out = new java.util.LinkedHashMap<>();
    for (TickerDay t : days) {
      out.put(t.ticker() + "@" + t.sessionDate(), t);
    }
    return out;
  }

  private static String tuples(int n, String tuple) {
    return String.join(", ", Collections.nCopies(n, tuple));
  }

  // ---------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------

  /**
   * Orders matching the query. Newest first by {@code first_ts}. When {@code query.date()} is set,
   * filters to orders whose {@code first_ts} falls in the ET session window [09:25, 16:20). The
   * {@code before} cursor enables keyset paging: pass the last row's {@code first_ts} to continue.
   */
  public List<Order> orders(OrderQuery query) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT order_id, ticker, contract, expiry, strike, right, first_ts, last_ts,"
                + " trade_count, exchange_count, size, premium, vwap, side, side_source,"
                + " bid, ask, quote_ts, sweep, multi_leg, auction, cross, block, unusual,"
                + " day_volume, open_interest, underlying_price, moneyness_pct, dte, conditions,"
                + " block_premium_threshold, block_size_threshold, order_window_ms, quote_max_age_ms"
                + " FROM flow_order WHERE TRUE");
    List<Object> binds = new ArrayList<>();

    if (query.date() != null) {
      OffsetDateTime start =
          query.date().atTime(SESSION_START_HOUR, SESSION_START_MIN).atZone(ET).toOffsetDateTime();
      OffsetDateTime end =
          query.date().atTime(SESSION_END_HOUR, SESSION_END_MIN).atZone(ET).toOffsetDateTime();
      sql.append(" AND first_ts >= ?::timestamptz AND first_ts < ?::timestamptz");
      binds.add(start);
      binds.add(end);
    }
    if (query.ticker() != null) {
      sql.append(" AND ticker = ?");
      binds.add(query.ticker());
    }
    if (query.minPremium() != null) {
      sql.append(" AND premium >= ?");
      binds.add(query.minPremium());
    }
    if (query.labels() != null) {
      for (String label : query.labels()) {
        switch (label) {
          case "sweep" -> {
            sql.append(" AND sweep = true");
          }
          case "block" -> {
            sql.append(" AND block = true");
          }
          case "multi_leg" -> {
            sql.append(" AND multi_leg = true");
          }
          case "unusual" -> {
            sql.append(" AND unusual = true");
          }
          default -> {
            /* unknown labels are ignored */
          }
        }
      }
    }
    if (query.right() != null) {
      sql.append(" AND right = ?");
      binds.add(query.right());
    }
    if (query.side() != null) {
      sql.append(" AND side = ?");
      binds.add(query.side());
    }
    if (query.dteMin() != null) {
      sql.append(" AND dte >= ?");
      binds.add(query.dteMin());
    }
    if (query.dteMax() != null) {
      sql.append(" AND dte <= ?");
      binds.add(query.dteMax());
    }
    if (query.before() != null) {
      sql.append(" AND first_ts < ?::timestamptz");
      binds.add(query.before());
    }
    sql.append(" ORDER BY first_ts DESC, id DESC LIMIT ?");
    binds.add(query.limit());

    Result<Record> rows = writerDsl.fetch(sql.toString(), binds.toArray());
    List<Order> out = new ArrayList<>(rows.size());
    for (Record r : rows) {
      out.add(
          new Order(
              r.get("order_id", String.class),
              r.get("ticker", String.class),
              r.get("contract", String.class),
              r.get("expiry", LocalDate.class),
              r.get("strike", BigDecimal.class),
              r.get("right", String.class),
              r.get("first_ts", OffsetDateTime.class),
              r.get("last_ts", OffsetDateTime.class),
              r.get("trade_count", Integer.class),
              r.get("exchange_count", Integer.class),
              r.get("size", Integer.class),
              r.get("premium", BigDecimal.class),
              r.get("vwap", BigDecimal.class),
              r.get("side", String.class),
              r.get("side_source", String.class),
              r.get("bid", BigDecimal.class),
              r.get("ask", BigDecimal.class),
              r.get("quote_ts", OffsetDateTime.class),
              r.get("sweep", Boolean.class),
              r.get("multi_leg", Boolean.class),
              r.get("auction", Boolean.class),
              r.get("cross", Boolean.class),
              r.get("block", Boolean.class),
              r.get("unusual", Boolean.class),
              r.get("day_volume", Long.class),
              r.get("open_interest", Integer.class),
              r.get("underlying_price", BigDecimal.class),
              r.get("moneyness_pct", BigDecimal.class),
              r.get("dte", Integer.class),
              r.get("conditions", String.class),
              r.get("block_premium_threshold", BigDecimal.class),
              r.get("block_size_threshold", Integer.class),
              r.get("order_window_ms", Integer.class),
              r.get("quote_max_age_ms", Integer.class)));
    }
    return out;
  }

  /**
   * Ticker-day rows for {@code date}, optionally filtered to one ticker. Ordered by ticker
   * ascending.
   */
  public List<TickerDay> summary(LocalDate date, String ticker) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT ticker, session_date, call_premium, put_premium, bought_premium,"
                + " sold_premium, mid_premium, unknown_premium, trade_count, order_count,"
                + " quoted_premium_share FROM flow_ticker_day WHERE session_date = ?::date");
    List<Object> binds = new ArrayList<>();
    binds.add(date);
    if (ticker != null) {
      sql.append(" AND ticker = ?");
      binds.add(ticker);
    }
    sql.append(" ORDER BY ticker");

    Result<Record> rows = writerDsl.fetch(sql.toString(), binds.toArray());
    List<TickerDay> out = new ArrayList<>(rows.size());
    for (Record r : rows) {
      out.add(
          new TickerDay(
              r.get("ticker", String.class),
              r.get("session_date", LocalDate.class),
              r.get("call_premium", BigDecimal.class),
              r.get("put_premium", BigDecimal.class),
              r.get("bought_premium", BigDecimal.class),
              r.get("sold_premium", BigDecimal.class),
              r.get("mid_premium", BigDecimal.class),
              r.get("unknown_premium", BigDecimal.class),
              r.get("trade_count", Integer.class),
              r.get("order_count", Integer.class),
              r.get("quoted_premium_share", BigDecimal.class)));
    }
    return out;
  }

  // ---------------------------------------------------------------------------
  // Retention
  // ---------------------------------------------------------------------------

  /**
   * Delete up to {@code batchLimit} orders whose {@code first_ts} is before {@code cutoff}, oldest
   * first. Bounded so one enormous first-run DELETE cannot hold row locks and inflate WAL.
   */
  public int deleteOrdersOlderThan(OffsetDateTime cutoff, int batchLimit) {
    if (batchLimit <= 0) {
      return 0;
    }
    return writerDsl.execute(
        "DELETE FROM flow_order WHERE id IN ("
            + " SELECT id FROM flow_order WHERE first_ts < ?::timestamptz"
            + " ORDER BY first_ts ASC LIMIT ?)",
        cutoff,
        batchLimit);
  }

  /**
   * Delete up to {@code batchLimit} ticker-days whose {@code session_date} is before {@code
   * cutoff}.
   */
  public int deleteTickerDaysOlderThan(LocalDate cutoff, int batchLimit) {
    if (batchLimit <= 0) {
      return 0;
    }
    return writerDsl.execute(
        "DELETE FROM flow_ticker_day WHERE (ticker, session_date) IN ("
            + " SELECT ticker, session_date FROM flow_ticker_day WHERE session_date < ?::date"
            + " ORDER BY session_date ASC LIMIT ?)",
        cutoff,
        batchLimit);
  }
}
