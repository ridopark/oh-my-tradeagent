package com.ohmytradeagent.tdbff.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.ohmytradeagent.tdbff.darkpool.DarkpoolRepository;
import com.ohmytradeagent.tdbff.darkpool.DarkpoolRepository.Bar;
import com.ohmytradeagent.tdbff.darkpool.DarkpoolRepository.IngestResult;
import com.ohmytradeagent.tdbff.darkpool.DarkpoolRepository.Level;
import com.ohmytradeagent.tdbff.darkpool.DarkpoolRepository.Print;
import com.ohmytradeagent.tdbff.darkpool.DarkpoolRepository.SymbolSummary;
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
 * Real-Postgres coverage for V15 (dark-pool prints + 5-minute bars) that runs {@link
 * DarkpoolRepository}'s ACTUAL SQL as {@code dashboard_writer} — the lesson of {@code
 * OptionsChatRepositoryIT}: a migration test with hand-written SQL and a mocked web test can both
 * be green while the repository's own statements have never executed.
 *
 * <p>Pins: the ON CONFLICT paths work under the V15 grants (PG16 needs SELECT on the conflict
 * target columns), a replayed print stores once, a re-posted bar updates in place, the session
 * summary matches a hand-computed fixture, and the privilege boundary (no UPDATE on prints for the
 * writer; readonly is SELECT-only) holds. Gated on {@code RUN_DB_ITS=true}.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class DarkpoolMigrationIT {

  private static final String READONLY_PW = "readonly-test-pw";
  private static final String WRITER_PW = "writer-test-pw";
  private static final String INSUFFICIENT_PRIVILEGE = "42501";

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  private static DarkpoolRepository repo;

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
    repo = new DarkpoolRepository(DSL.using(ds, SQLDialect.POSTGRES));
  }

  private static Connection asSuperuser() throws SQLException {
    return DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  private static Connection asRole(String role, String password) throws SQLException {
    return DriverManager.getConnection(postgres.getJdbcUrl(), role, password);
  }

  private static Print print(
      long tradeId, String symbol, String ts, String price, int size, String side) {
    BigDecimal p = new BigDecimal(price);
    return new Print(
        tradeId,
        symbol,
        OffsetDateTime.parse(ts),
        p,
        size,
        p.multiply(BigDecimal.valueOf(size)).setScale(2),
        "D",
        "@",
        "B",
        p.subtract(new BigDecimal("0.01")),
        p.add(new BigDecimal("0.01")),
        OffsetDateTime.parse(ts).minusNanos(50_000_000),
        side);
  }

  private static Bar bar(
      String symbol,
      String bucket,
      long dp,
      long total,
      long buy,
      long sell,
      int largeCount,
      long largeVolume,
      String vwap) {
    return new Bar(
        symbol,
        OffsetDateTime.parse(bucket),
        dp,
        (int) (dp / 100),
        total,
        (int) (total / 100),
        buy,
        sell,
        largeCount,
        largeVolume,
        vwap == null ? null : new BigDecimal(vwap));
  }

  @Test
  void tablesAndIndexesExist() throws SQLException {
    try (Connection c = asSuperuser();
        var st = c.createStatement()) {
      for (String t : new String[] {"darkpool_print", "darkpool_bar_5m"}) {
        try (var rs = st.executeQuery("SELECT to_regclass('public." + t + "') IS NOT NULL")) {
          rs.next();
          assertThat(rs.getBoolean(1)).as("%s table", t).isTrue();
        }
      }
      for (String idx :
          new String[] {
            "darkpool_print_symbol_ts_idx", "darkpool_print_ts_idx", "darkpool_bar_5m_bucket_idx"
          }) {
        assertThat(indexExists(c, idx)).as("index %s", idx).isTrue();
      }
    }
  }

  @Test
  void aReplayedPrintStoresOnce_provingTheConflictProbeSelectGrant() {
    Print p = print(7001L, "AAPL", "2026-10-26T15:00:00Z", "230.00", 1000, "buy");

    IngestResult first = repo.ingest(List.of(p), List.of());
    IngestResult replay = repo.ingest(List.of(p), List.of());

    assertThat(first.storedPrints()).isEqualTo(1);
    assertThat(replay.storedPrints()).as("replay is a silent no-op").isZero();
    assertThat(repo.recentPrints("AAPL", null, BigDecimal.ZERO, 10)).hasSize(1);
  }

  @Test
  void aRePostedBarUpdatesInPlace() {
    Bar partial = bar("MSFT", "2026-10-27T14:00:00Z", 100, 1000, 60, 40, 0, 0, "410.0000");
    Bar filled = bar("MSFT", "2026-10-27T14:00:00Z", 900, 5000, 500, 400, 2, 700, "410.1234");

    assertThat(repo.ingest(List.of(), List.of(partial)).storedBars()).isEqualTo(1);
    assertThat(repo.ingest(List.of(), List.of(filled)).storedBars()).isEqualTo(1);

    List<SymbolSummary> day = repo.summary(LocalDate.of(2026, 10, 27));
    assertThat(day).hasSize(1);
    SymbolSummary msft = day.get(0);
    assertThat(msft.symbol()).isEqualTo("MSFT");
    assertThat(msft.dpVolume()).as("the second post replaced the first").isEqualTo(900);
    assertThat(msft.totalVolume()).isEqualTo(5000);
    assertThat(msft.largeCount()).isEqualTo(2);
    assertThat(msft.largeVolume()).isEqualTo(700);
  }

  @Test
  void recentPrints_isNewestFirst_andHonoursEveryFilter() {
    repo.ingest(
        List.of(
            print(8001L, "AMD", "2026-10-25T14:00:00Z", "150.00", 100, "sell"),
            print(8002L, "AMD", "2026-10-25T15:00:00Z", "151.00", 2000, "buy"),
            print(8003L, "AMD", "2026-10-25T16:00:00Z", "152.00", 100, "mid"),
            print(8004L, "AMZN", "2026-10-25T16:30:00Z", "200.00", 5000, "unknown")),
        List.of());

    List<Print> all = repo.recentPrints("AMD", null, BigDecimal.ZERO, 10);
    assertThat(all).extracting(Print::tradeId).containsExactly(8003L, 8002L, 8001L);

    List<Print> since =
        repo.recentPrints("AMD", OffsetDateTime.parse("2026-10-25T15:00:00Z"), BigDecimal.ZERO, 10);
    assertThat(since).extracting(Print::tradeId).containsExactly(8003L, 8002L);

    List<Print> big = repo.recentPrints("AMD", null, new BigDecimal("200000"), 10);
    assertThat(big).extracting(Print::tradeId).containsExactly(8002L);

    List<Print> limited = repo.recentPrints("AMD", null, BigDecimal.ZERO, 1);
    assertThat(limited).extracting(Print::tradeId).containsExactly(8003L);

    // No symbol: every symbol, still newest first, still filtered.
    List<Print> everything =
        repo.recentPrints(null, OffsetDateTime.parse("2026-10-25T16:00:00Z"), BigDecimal.ZERO, 10);
    assertThat(everything).extracting(Print::tradeId).containsExactly(8004L, 8003L);

    Print row = all.get(1);
    assertThat(row.price()).isEqualByComparingTo("151.0000");
    assertThat(row.notional()).isEqualByComparingTo("302000.00");
    assertThat(row.bid()).isEqualByComparingTo("150.9900");
    assertThat(row.side()).isEqualTo("buy");
    // Instants, not OffsetDateTimes: the offset jOOQ hands back follows the session timezone.
    assertThat(row.quoteTs().toInstant())
        .isEqualTo(OffsetDateTime.parse("2026-10-25T14:59:59.950Z").toInstant());
    assertThat(row.ts().toInstant())
        .isEqualTo(OffsetDateTime.parse("2026-10-25T15:00:00Z").toInstant());
  }

  /**
   * Session 2026-10-28 (EDT, UTC-4), hand-computed. The summary is RTH-ONLY: bars whose bucket
   * starts in [09:30, 16:00) ET and prints in the same window; the late window is [14:00, 15:30)
   * ET, half-open.
   *
   * <p>QQQ bars (ET bucket → UTC): 09:30 → 13:30Z, 14:00 → 18:00Z, 15:30 → 19:30Z, 15:35 → 19:35Z;
   * plus a pre-market bar (08:00 → 12:00Z), a post-close bar (16:00 → 20:00Z) and one on the NEXT
   * session, all of which must be excluded.
   *
   * <pre>
   *   bucket   dp    total  buy   sell  large  largeVol   in RTH?  late-window?
   *   08:00    7777  7777   7777  0     7      7777       no       —
   *   09:30    1000  4000   600   300   1      500        yes      no
   *   14:00    2000  6000   500   1500  2      1500       yes      yes
   *   15:30    1000  2000   800   200   0      0          yes      no (exclusive end)
   *   15:35    500   1000   100   400   0      0          yes      no
   *   16:00    8888  8888   8888  0     8      8888       no       —
   *   sum(RTH) 4500  13000  2000  2400  3      2000
   * </pre>
   *
   * dp_share = 4500/13000 = 0.346153…; buy_ratio = 2000/4400 = 0.454545…; late_buy_ratio =
   * 500/(500+1500) = 0.25.
   *
   * <p>QQQ prints at/above the $200k large threshold, bucketed to 0.1% (bucket = floor(ln(price) /
   * ln(1.001)); bucket 6217 spans [1.001^6217, 1.001^6218) = [499.6429, 500.1425)):
   *
   * <pre>
   *   price   size  notional    bucket
   *   500.00  1000  500000.00   6217
   *   500.05  600   300030.00   6217   → level 500.02 (notional-weighted), 800030.00, 2 prints
   *   505.00  500   252500.00   6227   → level 505.00, 252500.00, 1
   *   495.00  450   222750.00   6207   → level 495.00, 222750.00, 1
   *   490.00  420   205800.00   6197   → 4th, dropped
   *   500.08  100   50008.00    —      → below the threshold
   *   510.00  2000  1020000.00  —      → 16:30 ET, after the close: excluded from the levels
   * </pre>
   *
   * <p>SPY has one all-zero bar (ratios must be null, not 0 or NaN) and one large print.
   */
  @Test
  void summary_matchesTheHandComputedSessionFixture() {
    repo.ingest(
        List.of(
            print(9001L, "QQQ", "2026-10-28T14:01:00Z", "500.00", 1000, "buy"),
            print(9002L, "QQQ", "2026-10-28T14:02:00Z", "500.05", 600, "sell"),
            print(9003L, "QQQ", "2026-10-28T15:00:00Z", "505.00", 500, "buy"),
            print(9004L, "QQQ", "2026-10-28T16:00:00Z", "495.00", 450, "mid"),
            print(9005L, "QQQ", "2026-10-28T17:00:00Z", "490.00", 420, "unknown"),
            print(9006L, "QQQ", "2026-10-28T18:00:00Z", "500.08", 100, "buy"),
            // The next ET session: 2026-10-29 09:30 ET.
            print(9007L, "QQQ", "2026-10-29T13:30:00Z", "600.00", 10000, "buy"),
            // Same date, after the close (16:30 ET): served by the feed, excluded from the summary.
            print(9009L, "QQQ", "2026-10-28T20:30:00Z", "510.00", 2000, "buy"),
            print(9008L, "SPY", "2026-10-28T19:00:00Z", "450.00", 1000, "sell")),
        List.of(
            // Pre-market (08:00 ET) and post-close (16:00 ET): outside RTH, excluded.
            bar("QQQ", "2026-10-28T12:00:00Z", 7777, 7777, 7777, 0, 7, 7777, "499.0000"),
            bar("QQQ", "2026-10-28T20:00:00Z", 8888, 8888, 8888, 0, 8, 8888, "499.0000"),
            bar("QQQ", "2026-10-28T13:30:00Z", 1000, 4000, 600, 300, 1, 500, "500.0000"),
            bar("QQQ", "2026-10-28T18:00:00Z", 2000, 6000, 500, 1500, 2, 1500, "501.0000"),
            bar("QQQ", "2026-10-28T19:30:00Z", 1000, 2000, 800, 200, 0, 0, "502.0000"),
            bar("QQQ", "2026-10-28T19:35:00Z", 500, 1000, 100, 400, 0, 0, "503.0000"),
            bar("QQQ", "2026-10-29T13:30:00Z", 9999, 9999, 9999, 0, 9, 9999, "600.0000"),
            bar("SPY", "2026-10-28T13:30:00Z", 0, 0, 0, 0, 0, 0, null)));

    List<SymbolSummary> day = repo.summary(LocalDate.of(2026, 10, 28));

    assertThat(day).extracting(SymbolSummary::symbol).containsExactly("QQQ", "SPY");

    SymbolSummary qqq = day.get(0);
    assertThat(qqq.dpVolume()).isEqualTo(4500);
    assertThat(qqq.totalVolume()).isEqualTo(13000);
    assertThat(qqq.dpShare()).isCloseTo(4500.0 / 13000.0, within(1e-12));
    assertThat(qqq.buyVolume()).isEqualTo(2000);
    assertThat(qqq.sellVolume()).isEqualTo(2400);
    assertThat(qqq.buyRatio()).isCloseTo(2000.0 / 4400.0, within(1e-12));
    assertThat(qqq.lateBuyRatio()).isCloseTo(500.0 / 2000.0, within(1e-12));
    assertThat(qqq.largeCount()).isEqualTo(3);
    assertThat(qqq.largeVolume()).isEqualTo(2000);
    assertThat(qqq.levels()).hasSize(3);
    assertLevel(qqq.levels().get(0), "500.02", "800030.00", 2);
    assertLevel(qqq.levels().get(1), "505.00", "252500.00", 1);
    assertLevel(qqq.levels().get(2), "495.00", "222750.00", 1);

    // The feed itself stays unfiltered: the after-close print (and the next session's) are served
    // even though the summary ignored them.
    assertThat(
            repo.recentPrints(
                "QQQ", OffsetDateTime.parse("2026-10-28T20:00:00Z"), BigDecimal.ZERO, 10))
        .extracting(Print::tradeId)
        .containsExactly(9007L, 9009L);

    SymbolSummary spy = day.get(1);
    assertThat(spy.dpVolume()).isZero();
    assertThat(spy.totalVolume()).isZero();
    assertThat(spy.dpShare()).isNull();
    assertThat(spy.buyRatio()).isNull();
    assertThat(spy.lateBuyRatio()).isNull();
    assertThat(spy.levels()).hasSize(1);
    assertLevel(spy.levels().get(0), "450.00", "450000.00", 1);
  }

  @Test
  void summary_ofAnEmptySessionIsEmpty() {
    assertThat(repo.summary(LocalDate.of(2020, 1, 1))).isEmpty();
  }

  @Test
  void retentionDeletesAsTheWriter_printsAndBars() {
    repo.ingest(
        List.of(
            print(9101L, "NVDA", "2025-01-02T15:00:00Z", "140.00", 100, "buy"),
            print(9102L, "NVDA", "2026-10-20T15:00:00Z", "180.00", 100, "buy")),
        List.of(
            bar("NVDA", "2025-01-02T15:00:00Z", 1, 1, 1, 0, 0, 0, null),
            bar("NVDA", "2026-10-20T15:00:00Z", 1, 1, 1, 0, 0, 0, null)));

    OffsetDateTime cutoff = OffsetDateTime.parse("2026-01-01T00:00:00Z");
    assertThat(repo.deletePrintsOlderThan(cutoff, 500)).isEqualTo(1);
    assertThat(repo.deleteBarsOlderThan(cutoff, 500)).isEqualTo(1);
    assertThat(repo.deletePrintsOlderThan(cutoff, 500)).as("nothing left to sweep").isZero();

    assertThat(repo.recentPrints("NVDA", null, BigDecimal.ZERO, 10))
        .extracting(Print::tradeId)
        .containsExactly(9102L);
  }

  @Test
  void writerCannotUpdatePrints_butCanUpdateBars() throws SQLException {
    try (Connection w = asRole("dashboard_writer", WRITER_PW);
        var st = w.createStatement()) {
      assertDenied(() -> st.executeUpdate("UPDATE darkpool_print SET side = 'buy' WHERE id = -1"));
      st.executeUpdate("UPDATE darkpool_bar_5m SET dp_volume = 0 WHERE symbol = 'nope'");
    }
  }

  @Test
  void readonlyCanSelectBothTables_andNothingElse() throws SQLException {
    try (Connection r = asRole("dashboard_readonly", READONLY_PW);
        var st = r.createStatement()) {
      st.executeQuery("SELECT count(*) FROM darkpool_print").close();
      st.executeQuery("SELECT count(*) FROM darkpool_bar_5m").close();
      assertDenied(() -> st.executeUpdate("DELETE FROM darkpool_print"));
      assertDenied(() -> st.executeUpdate("DELETE FROM darkpool_bar_5m"));
      assertDenied(
          () ->
              st.executeUpdate(
                  "INSERT INTO darkpool_bar_5m (symbol, bucket, dp_volume, dp_trades,"
                      + " total_volume, total_trades, buy_volume, sell_volume, large_count,"
                      + " large_volume) VALUES ('X', now(), 0, 0, 0, 0, 0, 0, 0, 0)"));
    }
  }

  @Test
  void theSideCheckRejectsAnUnknownValueAtTheDatabaseToo() throws SQLException {
    try (Connection w = asRole("dashboard_writer", WRITER_PW);
        var st = w.createStatement()) {
      assertThatThrownBy(
              () ->
                  st.executeUpdate(
                      "INSERT INTO darkpool_print (trade_id, symbol, ts, price, size, notional,"
                          + " side) VALUES (1, 'X', now(), 1, 1, 1, 'short')"))
          .isInstanceOf(SQLException.class)
          .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23514"));
    }
  }

  private static void assertLevel(Level level, String price, String notional, int prints) {
    assertThat(level.price()).isEqualByComparingTo(price);
    assertThat(level.notional()).isEqualByComparingTo(notional);
    assertThat(level.prints()).isEqualTo(prints);
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
