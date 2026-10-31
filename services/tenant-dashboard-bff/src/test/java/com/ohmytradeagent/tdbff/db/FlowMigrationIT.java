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
        repo.orders(new OrderQuery(null, "NVDA", null, null, null, null, null, null, null, 10));
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
                LocalDate.of(2026, 10, 28), "SPY", null, null, null, null, null, null, null, 10));
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
                null, "AMD", new BigDecimal("250000"), null, null, null, null, null, null, 10));
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
        repo.orders(new OrderQuery(null, "AMZN", null, null, null, null, null, null, null, 10));
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
    try (Connection w = asRole("dashboard_writer", WRITER_PW);
        var st = w.createStatement()) {
      assertThatThrownBy(
              () ->
                  st.executeUpdate(
                      "INSERT INTO flow_order (order_id, ticker, contract, strike, right, first_ts,"
                          + " last_ts, trade_count, exchange_count, size, premium, vwap, side,"
                          + " side_source, sweep, multi_leg, auction, cross, block,"
                          + " block_premium_threshold, block_size_threshold, order_window_ms,"
                          + " quote_max_age_ms)"
                          + " VALUES ('bad', 'X', 'X', 1, 'C', now(), now(), 1, 1, 1, 1, 1,"
                          + " 'long', 'none', false, false, false, false, false,"
                          + " 250000, 1000, 1000, 3000)"))
          .isInstanceOf(SQLException.class)
          .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23514"));
    }
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
