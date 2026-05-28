package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Realized PnL composition from audit_log JSONB. Gated on {@code RUN_DB_ITS=true} to match other
 * DB-backed ITs.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class DailyPnlActivitiesImplIT {

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  private static java.sql.Connection conn;
  private static DSLContext dsl;
  private DailyPnlActivitiesImpl svc;

  @BeforeAll
  static void initDb() throws Exception {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
    conn =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    dsl = DSL.using(conn, SQLDialect.POSTGRES);
  }

  @AfterAll
  static void closeDb() throws Exception {
    if (conn != null) conn.close();
  }

  @BeforeEach
  void truncate() {
    dsl.execute("DELETE FROM audit_log");
    svc = new DailyPnlActivitiesImpl(dsl);
  }

  @Test
  void computeRealizedPnl_entryAndExitMatch_realizesOnlyExitedCostBasis() {
    // Entry: 2 contracts @ 2.30 (cost basis 2.30/contract).
    insertAudit(
        "dev",
        "copytrade-v1",
        "EntryFilled",
        "2026-05-14T14:00:00Z",
        "{\"avg_fill_price\":\"2.30\",\"filled_qty\":2}");
    // Partial exit: 1 contract @ 3.10. Only the exited contract's cost basis realizes:
    // 1 * (3.10 - 2.30) * 100 = +80. The other open contract is excluded (issue #273) —
    // its debit is not booked as a loss until it exits.
    insertAudit(
        "dev",
        "copytrade-v1",
        "PartialExitFilled",
        "2026-05-14T17:30:00Z",
        "{\"avg_fill_price\":\"3.10\",\"qty_filled\":1}");

    BigDecimal pnl = svc.computeRealizedPnl("dev", "copytrade-v1", LocalDate.of(2026, 5, 14));

    assertThat(pnl).isEqualByComparingTo("80.00");
  }

  @Test
  void computeRealizedPnl_entryWithNoExit_excludesOpenDebit_issue273() {
    // Issue #273 regression: the 2026-05-28 fixture — two BTO entries, ZERO exits.
    // CRWV 12 @ 2.86 (= 12 * 2.86 * 100 = 3432 entry cost) and
    // PLTR 25 @ 0.88 (= 25 * 0.88 * 100 = 2200 entry cost). Combined entry notional
    // = 5632, which under the old realized-only-with-no-offset logic was booked as a
    // -5632 "realized loss" and tripped the $2,500 daily-loss kill switch on a normal
    // trading day. Realized P&L with zero exits must be $0 (no contract has been
    // exited, so no cost basis is realized), NOT -(entry notional).
    insertAudit(
        "dev",
        "copytrade-v1",
        "EntryFilled",
        "2026-05-28T14:00:00Z",
        "{\"avg_fill_price\":\"2.86\",\"filled_qty\":12}");
    insertAudit(
        "dev",
        "copytrade-v1",
        "EntryFilled",
        "2026-05-28T14:05:00Z",
        "{\"avg_fill_price\":\"0.88\",\"filled_qty\":25}");

    BigDecimal pnl = svc.computeRealizedPnl("dev", "copytrade-v1", LocalDate.of(2026, 5, 28));

    // Zero exits => zero realized P&L. Crucially > -2500, so the kill switch does not trip.
    assertThat(pnl).isEqualByComparingTo("0");
    assertThat(pnl.compareTo(new BigDecimal("-2500")) > 0).isTrue();
  }

  @Test
  void computeRealizedPnl_partialExit_realizesOnlyExitedCostBasis() {
    // Entry: 10 contracts @ 2.00 (cost basis 2.00/contract).
    insertAudit(
        "dev",
        "copytrade-v1",
        "EntryFilled",
        "2026-05-14T14:00:00Z",
        "{\"avg_fill_price\":\"2.00\",\"filled_qty\":10}");
    // Exit 4 contracts @ 3.00. Realized = 4 * (3.00 - 2.00) * 100 = +400.
    // The 6 still-open contracts contribute nothing (their debit stays excluded).
    insertAudit(
        "dev",
        "copytrade-v1",
        "PartialExitFilled",
        "2026-05-14T16:00:00Z",
        "{\"avg_fill_price\":\"3.00\",\"qty_filled\":4}");

    BigDecimal pnl = svc.computeRealizedPnl("dev", "copytrade-v1", LocalDate.of(2026, 5, 14));

    assertThat(pnl).isEqualByComparingTo("400.00");
  }

  @Test
  void computeRealizedPnl_fullExitAtLoss_realizesFullCostBasis() {
    // Entry: 5 @ 4.00 = cost basis 4.00/contract.
    insertAudit(
        "dev",
        "copytrade-v1",
        "EntryFilled",
        "2026-05-14T14:00:00Z",
        "{\"avg_fill_price\":\"4.00\",\"filled_qty\":5}");
    // Exit all 5 @ 1.00. Realized = 5 * (1.00 - 4.00) * 100 = -1500.
    insertAudit(
        "dev",
        "copytrade-v1",
        "PartialExitFilled",
        "2026-05-14T15:00:00Z",
        "{\"avg_fill_price\":\"1.00\",\"qty_filled\":5}");

    BigDecimal pnl = svc.computeRealizedPnl("dev", "copytrade-v1", LocalDate.of(2026, 5, 14));

    assertThat(pnl).isEqualByComparingTo("-1500.00");
  }

  @Test
  void computeRealizedPnl_crossSymbol_realizesAgainstOwnBasis_issue276() {
    // Issue #276 regression: two entries for DIFFERENT symbols at DIFFERENT prices, then an exit
    // for the SECOND symbol only. The exit MUST realize against its OWN symbol's entry basis, not
    // FIFO-match the foreign (first) symbol's cheaper basis.
    //
    // Symbol A (NVDA): 1 @ 1.00  (NOT exited — contributes nothing)
    // Symbol B (CRWV): 1 @ 5.00  -> exit 1 @ 6.00
    // Correct (per-symbol): exit B realizes (6.00 - 5.00) * 100 = +100.
    // Cross-matched (old, buggy pooled FIFO): exit would match A's 1.00 basis ->
    //   (6.00 - 1.00) * 100 = +500 against the WRONG symbol's basis.
    insertAudit(
        "dev",
        "copytrade-v1",
        "EntryFilled",
        "2026-05-14T14:00:00Z",
        "{\"avg_fill_price\":\"1.00\",\"filled_qty\":1,\"option_symbol\":\"NVDA  260516C00140000\"}");
    insertAudit(
        "dev",
        "copytrade-v1",
        "EntryFilled",
        "2026-05-14T14:05:00Z",
        "{\"avg_fill_price\":\"5.00\",\"filled_qty\":1,\"option_symbol\":\"CRWV  260516C00040000\"}");
    insertAudit(
        "dev",
        "copytrade-v1",
        "PartialExitFilled",
        "2026-05-14T16:00:00Z",
        "{\"avg_fill_price\":\"6.00\",\"qty_filled\":1,\"option_symbol\":\"CRWV  260516C00040000\"}");

    BigDecimal pnl = svc.computeRealizedPnl("dev", "copytrade-v1", LocalDate.of(2026, 5, 14));

    // Exit realizes against CRWV's own 5.00 basis: +100. NOT +500 (NVDA's foreign basis).
    assertThat(pnl).isEqualByComparingTo("100.00");
  }

  @Test
  void computeRealizedPnl_fractionalQty_isSkipped_issue276() {
    // Issue #276 [minor]: a fractional filled_qty must be SKIPPED (not crash via
    // longValueExact() ArithmeticException, which would crash-loop the activity under retry).
    // The fractional entry is skipped, leaving no same-day basis for the exit — the exit then
    // realizes its credit alone (no cost basis to net against).
    insertAudit(
        "dev",
        "copytrade-v1",
        "EntryFilled",
        "2026-05-14T14:00:00Z",
        "{\"avg_fill_price\":\"2.00\",\"filled_qty\":\"10.5\",\"option_symbol\":\"NVDA  260516C00140000\"}");
    // A clean integer entry for a second symbol that DOES match its exit.
    insertAudit(
        "dev",
        "copytrade-v1",
        "EntryFilled",
        "2026-05-14T14:05:00Z",
        "{\"avg_fill_price\":\"2.00\",\"filled_qty\":3,\"option_symbol\":\"CRWV  260516C00040000\"}");
    insertAudit(
        "dev",
        "copytrade-v1",
        "PartialExitFilled",
        "2026-05-14T16:00:00Z",
        "{\"avg_fill_price\":\"3.00\",\"qty_filled\":3,\"option_symbol\":\"CRWV  260516C00040000\"}");

    // CRWV exit realizes 3 * (3.00 - 2.00) * 100 = +300; the fractional NVDA entry is skipped so
    // the NVDA symbol contributes nothing and the activity never throws.
    BigDecimal pnl = svc.computeRealizedPnl("dev", "copytrade-v1", LocalDate.of(2026, 5, 14));

    assertThat(pnl).isEqualByComparingTo("300.00");
  }

  @Test
  void computeRealizedPnl_legacyRowsWithoutOptionSymbol_groupTogether_issue276() {
    // Issue #276: historical (pre-change) rows lack option_symbol. They must be TOLERATED — grouped
    // into a single no-symbol bucket and FIFO-matched among themselves exactly as before (never
    // NPE,
    // never cross-attribute against keyed rows). Entry 10 @ 2.00, exit 4 @ 3.00 => +400.
    insertAudit(
        "dev",
        "copytrade-v1",
        "EntryFilled",
        "2026-05-14T14:00:00Z",
        "{\"avg_fill_price\":\"2.00\",\"filled_qty\":10}");
    insertAudit(
        "dev",
        "copytrade-v1",
        "PartialExitFilled",
        "2026-05-14T16:00:00Z",
        "{\"avg_fill_price\":\"3.00\",\"qty_filled\":4}");

    BigDecimal pnl = svc.computeRealizedPnl("dev", "copytrade-v1", LocalDate.of(2026, 5, 14));

    assertThat(pnl).isEqualByComparingTo("400.00");
  }

  @Test
  void computeRealizedPnl_noRows_returnsZero() {
    BigDecimal pnl = svc.computeRealizedPnl("dev", "copytrade-v1", LocalDate.of(2026, 5, 14));

    assertThat(pnl).isEqualByComparingTo("0");
  }

  @Test
  void computeRealizedPnl_tenantScoped() {
    // Different tenant — should be excluded entirely (entry AND exit).
    insertAudit(
        "other",
        "copytrade-v1",
        "EntryFilled",
        "2026-05-14T14:00:00Z",
        "{\"avg_fill_price\":\"5.00\",\"filled_qty\":10}");
    insertAudit(
        "other",
        "copytrade-v1",
        "PartialExitFilled",
        "2026-05-14T16:00:00Z",
        "{\"avg_fill_price\":\"1.00\",\"qty_filled\":10}");
    // Same tenant — counted: 1 @ 2.00 entry, exit 1 @ 1.00 => 1 * (1.00 - 2.00) * 100 = -100.
    insertAudit(
        "dev",
        "copytrade-v1",
        "EntryFilled",
        "2026-05-14T14:00:00Z",
        "{\"avg_fill_price\":\"2.00\",\"filled_qty\":1}");
    insertAudit(
        "dev",
        "copytrade-v1",
        "PartialExitFilled",
        "2026-05-14T16:00:00Z",
        "{\"avg_fill_price\":\"1.00\",\"qty_filled\":1}");

    BigDecimal pnl = svc.computeRealizedPnl("dev", "copytrade-v1", LocalDate.of(2026, 5, 14));

    assertThat(pnl.doubleValue()).isCloseTo(-100.00, within(0.01));
  }

  @Test
  void computeRealizedPnl_dateScoped() {
    // Day before — excluded entirely (entry AND exit).
    insertAudit(
        "dev",
        "copytrade-v1",
        "EntryFilled",
        "2026-05-13T14:00:00Z",
        "{\"avg_fill_price\":\"5.00\",\"filled_qty\":10}");
    insertAudit(
        "dev",
        "copytrade-v1",
        "PartialExitFilled",
        "2026-05-13T16:00:00Z",
        "{\"avg_fill_price\":\"1.00\",\"qty_filled\":10}");
    // Trading day — counted: 1 @ 2.00 entry, exit 1 @ 1.00 => -100.
    insertAudit(
        "dev",
        "copytrade-v1",
        "EntryFilled",
        "2026-05-14T14:00:00Z",
        "{\"avg_fill_price\":\"2.00\",\"filled_qty\":1}");
    insertAudit(
        "dev",
        "copytrade-v1",
        "PartialExitFilled",
        "2026-05-14T18:00:00Z",
        "{\"avg_fill_price\":\"1.00\",\"qty_filled\":1}");

    BigDecimal pnl = svc.computeRealizedPnl("dev", "copytrade-v1", LocalDate.of(2026, 5, 14));

    assertThat(pnl.doubleValue()).isCloseTo(-100.00, within(0.01));
  }

  private static void insertAudit(
      String tenant, String strategy, String kind, String occurredAtIso, String subjectJson) {
    Timestamp ts = Timestamp.from(Instant.parse(occurredAtIso));
    dsl.execute(
        "INSERT INTO audit_log "
            + "(schema_version, tenant_id, strategy_id, event_id, occurred_at, kind, subject) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)",
        1,
        tenant,
        strategy,
        UUID.randomUUID(),
        ts,
        kind,
        subjectJson);
  }
}
