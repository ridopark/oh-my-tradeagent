package com.ohmytradeagent.tdbff.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ohmytradeagent.tdbff.flow.FlowRepository;
import com.ohmytradeagent.tdbff.flow.FlowRepository.IngestResult;
import com.ohmytradeagent.tdbff.flow.FlowRepository.Order;
import com.ohmytradeagent.tdbff.flow.FlowRepository.OrderQuery;
import com.ohmytradeagent.tdbff.flow.FlowRepository.TickerDay;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real-Postgres coverage for V16 (option-flow orders + ticker-day summaries) that runs {@link
 * FlowRepository}'s ACTUAL SQL as {@code dashboard_writer} — see {@code DarkpoolMigrationIT} for
 * the rationale: a migration structure test and mocked web test can both pass while the
 * repository's own statements have never executed.
 *
 * <p>Pins: ON CONFLICT paths work under the V16 grants (PG16 needs SELECT on conflict target
 * columns), a replayed order stores once (immutable), a re-posted ticker-day upserts in place, the
 * orders read honours all ten filters, the privilege boundary (no UPDATE on flow_order for the
 * writer; readonly is SELECT-only) holds. Gated on {@code RUN_DB_ITS=true}; Docker is unavailable
 * locally — CI runs it.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class FlowMigrationIT {

  private static final String READONLY_PW = "readonly-test-pw";
  private static final String WRITER_PW = "writer-test-pw";
  private static final String INSUFFICIENT_PRIVILEGE = "42501";

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  private static FlowRepository repo;

  @BeforeAll
  static void migrateAndWire() {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/dashboard")
        .placeholders(
            Map.of(
                "dashboard_readonly_password", READONLY_PW,
                "dashboard_writer_password", WRITER_PW,
                "trade_context_writer_password", "tc-writer-test-pw"))
        .load()
        .migrate();
    org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
    ds.setUrl(postgres.getJdbcUrl());
    ds.setUser("dashboard_writer");
    ds.setPassword(WRITER_PW);
    repo = new FlowRepository(DSL.using(ds, SQLDialect.POSTGRES));
  }

  private static Connection asSuperuser() throws SQLException {
    return DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  private static Connection asRole(String role, String password) throws SQLException {
    return DriverManager.getConnection(postgres.getJdbcUrl(), role, password);
  }

  private static Order order(String orderId, String ticker, String firstTs, String right) {
    return new Order(
        orderId,
        ticker,
        ticker + "240119" + right + "00200000",
        LocalDate.of(2024, 1, 19),
        new BigDecimal("200.00"),
        right,
        OffsetDateTime.parse(firstTs),
        OffsetDateTime.parse(firstTs),
        3,
        2,
        100,
        new BigDecimal("50000.00"),
        new BigDecimal("5.0000"),
        "buy",
        "quote",
        new BigDecimal("4.9000"),
        new BigDecimal("5.1000"),
        OffsetDateTime.parse(firstTs),
        false,
        false,
        false,
        false,
        false,
        null,
        null,
        null,
        null,
        null,
        80,
        "@",
        new BigDecimal("250000.00"),
        1000,
        1000,
        3000);
  }

  private static TickerDay tickerDay(String ticker, String date, BigDecimal call, BigDecimal put) {
    return new TickerDay(
        ticker,
        LocalDate.parse(date),
        call,
        put,
        call,
        put,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        3,
        1,
        new BigDecimal("1.0"));
  }

  @Test
  void tablesAndIndexesExist() throws SQLException {
    try (Connection c = asSuperuser();
        var st = c.createStatement()) {
      for (String t : new String[] {"flow_order", "flow_ticker_day"}) {
        try (var rs = st.executeQuery("SELECT to_regclass('public." + t + "') IS NOT NULL")) {
          rs.next();
          assertThat(rs.getBoolean(1)).as("%s table", t).isTrue();
        }
      }
      for (String idx :
          new String[] {
            "flow_order_first_ts_idx",
            "flow_order_ticker_first_ts_idx",
            "flow_ticker_day_session_date_idx"
          }) {
        assertThat(indexExists(c, idx)).as("index %s", idx).isTrue();
      }
    }
  }

  @Test
  void aReplayedOrderStoresOnce_provingTheConflictProbeSelectGrant() {
    Order o = order("IT:replay:1:buy", "NVDA", "2026-10-31T14:01:00Z", "C");

    IngestResult first = repo.ingest(List.of(o), List.of());
    IngestResult replay = repo.ingest(List.of(o), List.of());

    assertThat(first.storedOrders()).isEqualTo(1);
    assertThat(replay.storedOrders()).as("replay is a silent no-op").isZero();

    List<Order> rows =
        repo.orders(
                new OrderQuery(null, "NVDA", null, null, null, null, null, null, null, null, 10))
            .items();
    assertThat(rows).extracting(Order::orderId).contains("IT:replay:1:buy");
  }

  @Test
  void aRePostedTickerDayUpserts_replacingThePriorRow() {
    TickerDay partial =
        tickerDay("MSFT", "2026-10-27", new BigDecimal("10000.00"), BigDecimal.ZERO);
    TickerDay final_ =
        tickerDay("MSFT", "2026-10-27", new BigDecimal("90000.00"), new BigDecimal("5000.00"));

    assertThat(repo.ingest(List.of(), List.of(partial)).storedTickerDays()).isEqualTo(1);
    assertThat(repo.ingest(List.of(), List.of(final_)).storedTickerDays()).isEqualTo(1);

    List<TickerDay> days = repo.summary(LocalDate.of(2026, 10, 27), "MSFT");
    assertThat(days).hasSize(1);
    TickerDay msft = days.get(0);
    assertThat(msft.callPremium()).isEqualByComparingTo("90000.00");
    assertThat(msft.putPremium()).isEqualByComparingTo("5000.00");
    assertThat(msft.orderCount()).isEqualTo(1);
  }

  @Test
  void orders_dateFilter_restrictsToSessionWindow() {
    repo.ingest(
        List.of(
            // 2026-10-28 ET session (13:30Z = 09:30 ET): inside
            order("IT:date:inside", "SPY", "2026-10-28T13:30:00Z", "P"),
            // 2026-10-28 pre-market (12:00Z = 08:00 ET): outside [09:25, 16:20)
            order("IT:date:premarket", "SPY", "2026-10-28T12:00:00Z", "P"),
            // 2026-10-29: different session
            order("IT:date:next", "SPY", "2026-10-29T14:00:00Z", "P")),
        List.of());

    List<Order> results =
        repo.orders(
                new OrderQuery(
                    LocalDate.of(2026, 10, 28),
                    "SPY",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    10))
            .items();
    assertThat(results).extracting(Order::orderId).contains("IT:date:inside");
    assertThat(results).extracting(Order::orderId).doesNotContain("IT:date:premarket");
    assertThat(results).extracting(Order::orderId).doesNotContain("IT:date:next");
  }

  @Test
  void orders_premiumFilter_restrictsToMinimum() {
    repo.ingest(
        List.of(
            new Order(
                "IT:prem:big",
                "AMD",
                "AMD240119C00100000",
                LocalDate.of(2024, 1, 19),
                new BigDecimal("100.00"),
                "C",
                OffsetDateTime.parse("2026-10-31T15:00:00Z"),
                OffsetDateTime.parse("2026-10-31T15:00:00Z"),
                1,
                1,
                1000,
                new BigDecimal("300000.00"),
                new BigDecimal("3.0000"),
                "buy",
                "quote",
                null,
                null,
                OffsetDateTime.parse("2026-10-31T14:59:59Z"),
                false,
                false,
                false,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                30,
                null,
                new BigDecimal("250000.00"),
                1000,
                1000,
                3000),
            new Order(
                "IT:prem:small",
                "AMD",
                "AMD240119C00100000",
                LocalDate.of(2024, 1, 19),
                new BigDecimal("100.00"),
                "C",
                OffsetDateTime.parse("2026-10-31T15:01:00Z"),
                OffsetDateTime.parse("2026-10-31T15:01:00Z"),
                1,
                1,
                10,
                new BigDecimal("3000.00"),
                new BigDecimal("3.0000"),
                "buy",
                "quote",
                null,
                null,
                OffsetDateTime.parse("2026-10-31T15:00:59Z"),
                false,
                false,
                false,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                30,
                null,
                new BigDecimal("250000.00"),
                1000,
                1000,
                3000)),
        List.of());

    List<Order> bigOnly =
        repo.orders(
                new OrderQuery(
                    null,
                    "AMD",
                    new BigDecimal("250000"),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    10))
            .items();
    assertThat(bigOnly).extracting(Order::orderId).containsExactly("IT:prem:big");
  }

  @Test
  void orders_returnedNewestFirst() {
    repo.ingest(
        List.of(
            order("IT:sort:old", "AMZN", "2026-10-31T14:00:00Z", "C"),
            order("IT:sort:new", "AMZN", "2026-10-31T15:00:00Z", "C")),
        List.of());

    List<Order> results =
        repo.orders(
                new OrderQuery(null, "AMZN", null, null, null, null, null, null, null, null, 10))
            .items();
    // newest first
    List<String> ids = results.stream().map(Order::orderId).toList();
    assertThat(ids.indexOf("IT:sort:new")).isLessThan(ids.indexOf("IT:sort:old"));
  }

  @Test
  void retentionDeletesOrdersAndTickerDays_asTheWriter() {
    repo.ingest(
        List.of(order("IT:ret:old", "GOOG", "2025-01-02T15:00:00Z", "C")),
        List.of(tickerDay("GOOG", "2025-01-02", new BigDecimal("5000.00"), BigDecimal.ZERO)));

    OffsetDateTime orderCutoff = OffsetDateTime.parse("2026-01-01T00:00:00Z");
    int deletedOrders = repo.deleteOrdersOlderThan(orderCutoff, 500);
    assertThat(deletedOrders).isGreaterThanOrEqualTo(1);

    LocalDate dayCutoff = LocalDate.of(2026, 1, 1);
    int deletedDays = repo.deleteTickerDaysOlderThan(dayCutoff, 500);
    assertThat(deletedDays).isGreaterThanOrEqualTo(1);

    // A second pass finds nothing.
    assertThat(repo.deleteOrdersOlderThan(orderCutoff, 500)).as("nothing left").isZero();
  }

  @Test
  void writerCannotUpdateOrders_butCanUpdateTickerDays() throws SQLException {
    try (Connection w = asRole("dashboard_writer", WRITER_PW);
        var st = w.createStatement()) {
      assertDenied(
          () -> st.executeUpdate("UPDATE flow_order SET side = 'sell' WHERE order_id = 'nope'"));
      // Ticker-days are mutable (daily re-posts): UPDATE must be permitted.
      st.executeUpdate("UPDATE flow_ticker_day SET trade_count = 0 WHERE ticker = 'nope'");
    }
  }

  @Test
  void readonlyCanSelectBothTables_andNothingElse() throws SQLException {
    try (Connection r = asRole("dashboard_readonly", READONLY_PW);
        var st = r.createStatement()) {
      st.executeQuery("SELECT count(*) FROM flow_order").close();
      st.executeQuery("SELECT count(*) FROM flow_ticker_day").close();
      assertDenied(() -> st.executeUpdate("DELETE FROM flow_order"));
      assertDenied(() -> st.executeUpdate("DELETE FROM flow_ticker_day"));
      assertDenied(
          () ->
              st.executeUpdate(
                  "INSERT INTO flow_ticker_day (ticker, session_date, call_premium, put_premium,"
                      + " bought_premium, sold_premium, mid_premium, unknown_premium, trade_count,"
                      + " order_count) VALUES ('X', '2026-01-01', 0, 0, 0, 0, 0, 0, 0, 0)"));
    }
  }

  @Test
  void theSideCheckRejectsAnUnknownValueAtTheDatabaseToo() throws SQLException {
    // Must include ALL NOT NULL columns so the row fails on the CHECK (23514) not on a
    // NOT NULL violation (23502).  option_right / is_cross: `right` and `cross` are RESERVED
    // keywords in PostgreSQL 16.
    try (Connection w = asRole("dashboard_writer", WRITER_PW);
        var st = w.createStatement()) {
      assertThatThrownBy(
              () ->
                  st.executeUpdate(
                      "INSERT INTO flow_order (order_id, ticker, contract, expiry, strike,"
                          + " option_right, first_ts, last_ts, trade_count, exchange_count, size,"
                          + " premium, vwap, side, side_source, sweep, multi_leg, auction,"
                          + " is_cross, block, dte, block_premium_threshold,"
                          + " block_size_threshold, order_window_ms, quote_max_age_ms)"
                          + " VALUES ('bad-side', 'X', 'X', '2024-01-19', 1, 'C',"
                          + " now(), now(), 1, 1, 1, 1, 1,"
                          + " 'long', 'none', false, false, false, false, false,"
                          + " 30, 250000, 1000, 1000, 3000)"))
          .isInstanceOf(SQLException.class)
          .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23514"));
    }
  }

  /**
   * 12-order fixture covering every SQL-level filter: ET date bounds, ticker, min_premium, each
   * label (sweep/block/multi_leg/unusual), option_right (C/P), side (buy/sell/mid/unknown), and DTE
   * range. Proves the repository SQL is correct for all filter combinations at once without a mock.
   */
  @Test
  void allFiltersAtTheSqlLevel_12OrderFixture() {
    // Session anchor: 2026-10-29 13:30 UTC = 09:30 ET (inside [09:25, 16:20)).
    String inSession = "2026-10-29T13:30:00Z";
    // Pre-session: 2026-10-29 12:00 UTC = 08:00 ET (outside [09:25, 16:20)).
    String preSession = "2026-10-29T12:00:00Z";

    repo.ingest(
        List.of(
            // 1. in-session reference order (ticker=TSLA, C, buy, $50k, dte=30)
            makeOrder(
                "IT:12:in-session",
                "TSLA",
                inSession,
                "C",
                "buy",
                "50000",
                30,
                false,
                false,
                false,
                null),
            // 2. pre-session: outside ET window, must be excluded by date filter
            makeOrder(
                "IT:12:premarket",
                "TSLA",
                preSession,
                "C",
                "buy",
                "50000",
                30,
                false,
                false,
                false,
                null),
            // 3. different ticker (NVDA): excluded by ticker filter
            makeOrder(
                "IT:12:nvda",
                "NVDA",
                inSession,
                "C",
                "buy",
                "50000",
                30,
                false,
                false,
                false,
                null),
            // 4. high premium ($150k): included by min_premium=100000, excluded by =200000
            makeOrder(
                "IT:12:big-prem",
                "TSLA",
                inSession,
                "C",
                "buy",
                "150000",
                30,
                false,
                false,
                false,
                null),
            // 5. sweep=true
            makeOrder(
                "IT:12:sweep",
                "TSLA",
                inSession,
                "C",
                "buy",
                "50000",
                30,
                true,
                false,
                false,
                null),
            // 6. block=true
            makeOrder(
                "IT:12:block",
                "TSLA",
                inSession,
                "C",
                "buy",
                "50000",
                30,
                false,
                true,
                false,
                null),
            // 7. multi_leg=true
            makeOrder(
                "IT:12:multileg",
                "TSLA",
                inSession,
                "C",
                "buy",
                "50000",
                30,
                false,
                false,
                true,
                null),
            // 8. unusual=true
            makeOrder(
                "IT:12:unusual",
                "TSLA",
                inSession,
                "C",
                "buy",
                "50000",
                30,
                false,
                false,
                false,
                true),
            // 9. option_right=P
            makeOrder(
                "IT:12:put",
                "TSLA",
                inSession,
                "P",
                "sell",
                "50000",
                30,
                false,
                false,
                false,
                null),
            // 10. side=sell (C)
            makeOrder(
                "IT:12:sell",
                "TSLA",
                inSession,
                "C",
                "sell",
                "50000",
                30,
                false,
                false,
                false,
                null),
            // 11. short DTE (dte=2)
            makeOrder(
                "IT:12:dte-short",
                "TSLA",
                inSession,
                "C",
                "buy",
                "50000",
                2,
                false,
                false,
                false,
                null),
            // 12. long DTE (dte=60)
            makeOrder(
                "IT:12:dte-long",
                "TSLA",
                inSession,
                "C",
                "buy",
                "50000",
                60,
                false,
                false,
                false,
                null)),
        List.of());

    LocalDate oct29 = LocalDate.of(2026, 10, 29);

    // ET date bounds: only in-session orders returned.
    List<String> inSessionIds =
        repo
            .orders(
                new OrderQuery(oct29, "TSLA", null, null, null, null, null, null, null, null, 100))
            .items()
            .stream()
            .map(Order::orderId)
            .toList();
    assertThat(inSessionIds).doesNotContain("IT:12:premarket", "IT:12:nvda");

    // Ticker filter: NVDA row returned, TSLA rows absent.
    List<String> nvdaIds =
        repo
            .orders(
                new OrderQuery(oct29, "NVDA", null, null, null, null, null, null, null, null, 100))
            .items()
            .stream()
            .map(Order::orderId)
            .toList();
    assertThat(nvdaIds).contains("IT:12:nvda");
    assertThat(nvdaIds).doesNotContain("IT:12:in-session");

    // min_premium=100000: big-prem included, $50k rows excluded.
    List<String> bigIds =
        repo
            .orders(
                new OrderQuery(
                    oct29,
                    "TSLA",
                    new BigDecimal("100000"),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    100))
            .items()
            .stream()
            .map(Order::orderId)
            .toList();
    assertThat(bigIds).contains("IT:12:big-prem");
    assertThat(bigIds).doesNotContain("IT:12:in-session");

    // Label filters (each tested independently).
    assertHasOnly(
        "IT:12:sweep",
        repo.orders(
                new OrderQuery(
                    oct29, "TSLA", null, Set.of("sweep"), null, null, null, null, null, null, 100))
            .items());
    assertHasOnly(
        "IT:12:block",
        repo.orders(
                new OrderQuery(
                    oct29, "TSLA", null, Set.of("block"), null, null, null, null, null, null, 100))
            .items());
    assertHasOnly(
        "IT:12:multileg",
        repo.orders(
                new OrderQuery(
                    oct29,
                    "TSLA",
                    null,
                    Set.of("multi_leg"),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    100))
            .items());
    assertHasOnly(
        "IT:12:unusual",
        repo.orders(
                new OrderQuery(
                    oct29,
                    "TSLA",
                    null,
                    Set.of("unusual"),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    100))
            .items());

    // option_right=P: only the put row.
    assertHasOnly(
        "IT:12:put",
        repo.orders(
                new OrderQuery(oct29, "TSLA", null, null, "P", null, null, null, null, null, 100))
            .items());

    // side=sell: sell rows.
    List<String> sellIds =
        repo
            .orders(
                new OrderQuery(
                    oct29, "TSLA", null, null, null, "sell", null, null, null, null, 100))
            .items()
            .stream()
            .map(Order::orderId)
            .toList();
    assertThat(sellIds).contains("IT:12:sell", "IT:12:put");
    assertThat(sellIds).doesNotContain("IT:12:in-session");

    // DTE range: dte_min=3 excludes the short-DTE order (dte=2).
    List<String> longDteIds =
        repo
            .orders(new OrderQuery(oct29, "TSLA", null, null, null, null, 3, null, null, null, 100))
            .items()
            .stream()
            .map(Order::orderId)
            .toList();
    assertThat(longDteIds).doesNotContain("IT:12:dte-short");
    assertThat(longDteIds).contains("IT:12:dte-long");

    // dte_max=5 excludes the long-DTE order (dte=60).
    List<String> shortDteIds =
        repo
            .orders(new OrderQuery(oct29, "TSLA", null, null, null, null, null, 5, null, null, 100))
            .items()
            .stream()
            .map(Order::orderId)
            .toList();
    assertThat(shortDteIds).doesNotContain("IT:12:dte-long");
    assertThat(shortDteIds).contains("IT:12:dte-short");
  }

  /**
   * Compound cursor: ≥3 orders sharing the same {@code first_ts} are each returned exactly once
   * across a page boundary. A simple {@code first_ts < ?} cursor would skip the same-timestamp rows
   * that fall on the second page.
   */
  @Test
  void compoundCursorPaging_rowsSharingFirstTs_eachReturnedExactlyOnce() {
    // 3 AAPL orders with the same first_ts — they must not be grouped in a single window test
    // (different order_ids, inserted separately so each gets its own auto-increment id).
    String ts = "2026-11-01T14:00:00Z";
    repo.ingest(
        List.of(
            makeOrder("IT:page:1", "AAPL", ts, "C", "buy", "50000", 30, false, false, false, null),
            makeOrder("IT:page:2", "AAPL", ts, "C", "buy", "50000", 30, false, false, false, null),
            makeOrder("IT:page:3", "AAPL", ts, "C", "buy", "50000", 30, false, false, false, null)),
        List.of());

    // Page 1: limit=2. Second row's id is the compound cursor.
    FlowRepository.OrderPage p1 =
        repo.orders(
            new OrderQuery(null, "AAPL", null, null, null, null, null, null, null, null, 2));
    List<String> p1Ids = p1.items().stream().map(Order::orderId).toList();
    assertThat(p1Ids).hasSize(2);
    assertThat(p1.lastFirstTs()).isNotNull();
    assertThat(p1.lastId()).isNotNull();

    // Page 2: compound cursor → must return the remaining row.
    FlowRepository.OrderPage p2 =
        repo.orders(
            new OrderQuery(
                null,
                "AAPL",
                null,
                null,
                null,
                null,
                null,
                null,
                p1.lastFirstTs(),
                p1.lastId(),
                2));
    List<String> p2Ids = p2.items().stream().map(Order::orderId).toList();
    assertThat(p2Ids).hasSize(1);

    // Together: all three rows, each exactly once.
    assertThat(p1Ids).doesNotContainAnyElementsOf(p2Ids);
    assertThat(p1Ids)
        .hasSameElementsAs(
            List.of("IT:page:1", "IT:page:2", "IT:page:3").stream()
                .filter(id -> !p2Ids.contains(id))
                .toList());
    assertThat(Stream.concat(p1Ids.stream(), p2Ids.stream()).toList())
        .containsExactlyInAnyOrder("IT:page:1", "IT:page:2", "IT:page:3");
  }

  private static Order makeOrder(
      String orderId,
      String ticker,
      String firstTs,
      String right,
      String side,
      String premium,
      int dte,
      boolean sweep,
      boolean block,
      boolean multiLeg,
      Boolean unusual) {
    BigDecimal prem = new BigDecimal(premium + ".00");
    return new Order(
        orderId,
        ticker,
        ticker + "240119" + right + "00200000",
        LocalDate.of(2024, 1, 19),
        new BigDecimal("200.00"),
        right,
        OffsetDateTime.parse(firstTs),
        OffsetDateTime.parse(firstTs),
        1,
        1,
        100,
        prem,
        prem.divide(new BigDecimal("10000"), 4, java.math.RoundingMode.HALF_UP),
        side,
        "quote",
        null,
        null,
        OffsetDateTime.parse(firstTs),
        sweep,
        multiLeg,
        false,
        false,
        block,
        unusual,
        null,
        null,
        null,
        null,
        dte,
        "@",
        new BigDecimal("250000.00"),
        1000,
        1000,
        3000);
  }

  /** Asserts that the {@code items} list contains exactly one order with the given order_id. */
  private static void assertHasOnly(String orderId, List<Order> items) {
    assertThat(items)
        .as("only %s matches the filter", orderId)
        .extracting(Order::orderId)
        .containsExactly(orderId);
  }

  private static boolean indexExists(Connection c, String indexName) throws SQLException {
    try (var ps = c.prepareStatement("SELECT 1 FROM pg_indexes WHERE indexname = ?")) {
      ps.setString(1, indexName);
      try (var rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  private static void assertDenied(ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOf(SQLException.class)
        .satisfies(
            e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
  }
}
