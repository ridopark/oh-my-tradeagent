package com.ohmytradeagent.tdbff.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.UUID;
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
 * Parity check for the FIFO realized-PnL algorithm COPIED FROM {@code DailyPnlActivitiesImpl}. Uses
 * the same audit_log JSONB shape and the issue #273/#276 regression fixtures so the BFF's copy and
 * the orchestrator's source stay in lockstep. Gated on {@code RUN_DB_ITS=true} like the other
 * DB-backed ITs. The audit_log DDL is inlined (the BFF does not own that schema) — only the columns
 * the calculator reads are created.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class RealizedPnlCalculatorIT {

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  private static java.sql.Connection conn;
  private static DSLContext dsl;
  private RealizedPnlCalculator svc;

  @BeforeAll
  static void initDb() throws Exception {
    conn =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    dsl = DSL.using(conn, SQLDialect.POSTGRES);
    dsl.execute(
        "CREATE TABLE audit_log ("
            + "  id BIGSERIAL PRIMARY KEY,"
            + "  tenant_id VARCHAR(64) NOT NULL,"
            + "  strategy_id VARCHAR(64) NOT NULL,"
            + "  event_id UUID NOT NULL UNIQUE,"
            + "  occurred_at TIMESTAMPTZ NOT NULL,"
            + "  kind VARCHAR(64) NOT NULL,"
            + "  subject JSONB NOT NULL)");
  }

  @AfterAll
  static void closeDb() throws Exception {
    if (conn != null) conn.close();
  }

  @BeforeEach
  void reset() {
    dsl.execute("DELETE FROM audit_log");
    svc = new RealizedPnlCalculator(dsl);
  }

  @Test
  void entryAndPartialExit_realizesOnlyExitedCostBasis() {
    insertAudit(
        "EntryFilled", "2026-05-14T14:00:00Z", "{\"avg_fill_price\":\"2.30\",\"filled_qty\":2}");
    insertAudit(
        "PartialExitFilled",
        "2026-05-14T17:30:00Z",
        "{\"avg_fill_price\":\"3.10\",\"qty_filled\":1}");

    BigDecimal pnl = svc.computeRealizedPnl("dev", "copytrade-v1", LocalDate.of(2026, 5, 14));

    assertThat(pnl).isEqualByComparingTo("80.00"); // 1 * (3.10 - 2.30) * 100
  }

  @Test
  void entryWithNoExit_excludesOpenDebit_issue273() {
    insertAudit(
        "EntryFilled", "2026-05-28T14:00:00Z", "{\"avg_fill_price\":\"2.86\",\"filled_qty\":12}");
    insertAudit(
        "EntryFilled", "2026-05-28T14:05:00Z", "{\"avg_fill_price\":\"0.88\",\"filled_qty\":25}");

    BigDecimal pnl = svc.computeRealizedPnl("dev", "copytrade-v1", LocalDate.of(2026, 5, 28));

    assertThat(pnl).isEqualByComparingTo("0"); // zero exits => zero realized
  }

  @Test
  void crossSymbol_realizesAgainstOwnBasis_issue276() {
    insertAudit(
        "EntryFilled",
        "2026-05-14T14:00:00Z",
        "{\"avg_fill_price\":\"1.00\",\"filled_qty\":1,\"option_symbol\":\"NVDA  260516C00140000\"}");
    insertAudit(
        "EntryFilled",
        "2026-05-14T14:05:00Z",
        "{\"avg_fill_price\":\"5.00\",\"filled_qty\":1,\"option_symbol\":\"CRWV  260516C00040000\"}");
    insertAudit(
        "PartialExitFilled",
        "2026-05-14T16:00:00Z",
        "{\"avg_fill_price\":\"6.00\",\"qty_filled\":1,\"option_symbol\":\"CRWV  260516C00040000\"}");

    BigDecimal pnl = svc.computeRealizedPnl("dev", "copytrade-v1", LocalDate.of(2026, 5, 14));

    assertThat(pnl).isEqualByComparingTo("100.00"); // CRWV's own basis, not NVDA's foreign 1.00
  }

  @Test
  void allTime_matchesCrossDayCostBasis_noPhantomGain_issue276() {
    // Entry on day 1, partial exit on day 2 (different America/New_York dates). The all-time calc
    // drops the per-day predicate so the day-2 exit FIFO-matches the day-1 entry's real cost basis.
    insertAudit(
        "EntryFilled", "2026-05-14T14:00:00Z", "{\"avg_fill_price\":\"2.30\",\"filled_qty\":2}");
    insertAudit(
        "PartialExitFilled",
        "2026-05-15T17:30:00Z",
        "{\"avg_fill_price\":\"3.10\",\"qty_filled\":2}");

    // The day-scoped calc on day 2 sees ONLY the exit -> phantom raw proceeds 2 * 3.10 * 100 = 620.
    BigDecimal dayScoped = svc.computeRealizedPnl("dev", "copytrade-v1", LocalDate.of(2026, 5, 15));
    assertThat(dayScoped).isEqualByComparingTo("620.00"); // documented phantom gain (#276 §4)

    // The all-time calc matches the real basis -> 2 * (3.10 - 2.30) * 100 = 160.
    BigDecimal allTime = svc.computeRealizedPnlAllTime("dev", "copytrade-v1");
    assertThat(allTime).isEqualByComparingTo("160.00");
  }

  @Test
  void allTime_dramLiveLoss_matchesPriorDayBasis() {
    // Live prod_real 2026-06-29: bought 3 DRAM @ 2.3533 on 6/26, sold 2 @ 1.84 on 6/29.
    insertAudit(
        "EntryFilled",
        "2026-06-26T14:00:00Z",
        "{\"avg_fill_price\":\"2.3533\",\"filled_qty\":3,\"option_symbol\":\"DRAM  260717C00030000\"}");
    insertAudit(
        "PartialExitFilled",
        "2026-06-29T18:00:00Z",
        "{\"avg_fill_price\":\"1.84\",\"qty_filled\":2,\"option_symbol\":\"DRAM  260717C00030000\"}");

    BigDecimal allTime = svc.computeRealizedPnlAllTime("dev", "copytrade-v1");
    assertThat(allTime).isEqualByComparingTo("-102.66"); // 2 * (1.84 - 2.3533) * 100
  }

  private void insertAudit(String kind, String occurredAtIso, String subjectJson) {
    dsl.execute(
        "INSERT INTO audit_log (tenant_id, strategy_id, event_id, occurred_at, kind, subject)"
            + " VALUES (?, ?, ?, ?::timestamptz, ?, ?::jsonb)",
        "dev",
        "copytrade-v1",
        UUID.randomUUID(),
        occurredAtIso,
        kind,
        subjectJson);
  }
}
