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
  void computeRealizedPnl_entryAndExitMatch_returnsNetPremiumTimesMultiplier() {
    // Entry: 2 contracts * 2.30 fill * 100 = 460 debit
    insertAudit(
        "dev",
        "copytrade-v1",
        "EntryFilled",
        "2026-05-14T14:00:00Z",
        "{\"avg_fill_price\":\"2.30\",\"filled_qty\":2}");
    // Partial exit: 1 contract * 3.10 fill * 100 = 310 credit
    insertAudit(
        "dev",
        "copytrade-v1",
        "PartialExitFilled",
        "2026-05-14T17:30:00Z",
        "{\"avg_fill_price\":\"3.10\",\"qty_filled\":1}");

    BigDecimal pnl = svc.computeRealizedPnl("dev", "copytrade-v1", LocalDate.of(2026, 5, 14));

    // 310 - 460 = -150
    assertThat(pnl).isEqualByComparingTo("-150.00");
  }

  @Test
  void computeRealizedPnl_noRows_returnsZero() {
    BigDecimal pnl = svc.computeRealizedPnl("dev", "copytrade-v1", LocalDate.of(2026, 5, 14));

    assertThat(pnl).isEqualByComparingTo("0");
  }

  @Test
  void computeRealizedPnl_tenantScoped() {
    // Different tenant — should be excluded.
    insertAudit(
        "other",
        "copytrade-v1",
        "EntryFilled",
        "2026-05-14T14:00:00Z",
        "{\"avg_fill_price\":\"5.00\",\"filled_qty\":10}");
    // Same tenant — counted.
    insertAudit(
        "dev",
        "copytrade-v1",
        "EntryFilled",
        "2026-05-14T14:00:00Z",
        "{\"avg_fill_price\":\"2.00\",\"filled_qty\":1}");

    BigDecimal pnl = svc.computeRealizedPnl("dev", "copytrade-v1", LocalDate.of(2026, 5, 14));

    assertThat(pnl.doubleValue()).isCloseTo(-200.00, within(0.01));
  }

  @Test
  void computeRealizedPnl_dateScoped() {
    // Day before — excluded.
    insertAudit(
        "dev",
        "copytrade-v1",
        "EntryFilled",
        "2026-05-13T14:00:00Z",
        "{\"avg_fill_price\":\"5.00\",\"filled_qty\":10}");
    insertAudit(
        "dev",
        "copytrade-v1",
        "EntryFilled",
        "2026-05-14T18:00:00Z",
        "{\"avg_fill_price\":\"2.00\",\"filled_qty\":1}");

    BigDecimal pnl = svc.computeRealizedPnl("dev", "copytrade-v1", LocalDate.of(2026, 5, 14));

    assertThat(pnl.doubleValue()).isCloseTo(-200.00, within(0.01));
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
