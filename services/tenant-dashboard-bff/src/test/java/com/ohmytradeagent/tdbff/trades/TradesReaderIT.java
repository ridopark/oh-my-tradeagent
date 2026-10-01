package com.ohmytradeagent.tdbff.trades;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
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
 * SQL-level coverage for {@link TradesReader} against a real Postgres — the key assertion is TENANT
 * ISOLATION: the {@code tenant_id = ? AND strategy_id IN (...)} scoping must never return another
 * tenant's rows (nor a strategy the caller didn't ask for), and the kind filter must keep it to the
 * two fill kinds. Gated on {@code RUN_DB_ITS=true} like the other DB-backed ITs; the {@code
 * audit_log} DDL is inlined (the BFF does not own that schema) with only the columns the reader
 * touches.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class TradesReaderIT {

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  private static Connection conn;
  private static DSLContext dsl;
  private TradesReader reader;

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
            + "  actor VARCHAR(128),"
            + "  workflow_id VARCHAR(256),"
            + "  correlation_id VARCHAR(96),"
            + "  subject JSONB NOT NULL)");
  }

  @AfterAll
  static void closeDb() throws Exception {
    if (conn != null) {
      conn.close();
    }
  }

  @BeforeEach
  void reset() {
    dsl.execute("DELETE FROM audit_log");
    reader = new TradesReader(dsl);
  }

  @Test
  void scopesToRequestedTenantAndStrategyAndFillKindsOnly() {
    insert("dev", "s1", "EntryFilled", "2026-05-14T14:00:00Z");
    insert("dev", "s1", "PartialExitFilled", "2026-05-14T15:00:00Z");
    insert("other", "s1", "EntryFilled", "2026-05-14T14:00:00Z"); // different tenant, same strat id
    insert("dev", "s2", "EntryFilled", "2026-05-14T14:00:00Z"); // same tenant, strat not requested
    insert("dev", "s1", "SignalReceived", "2026-05-14T14:00:00Z"); // non-fill kind

    List<Map<String, Object>> items = reader.trades("dev", List.of("s1"), null, 100);

    assertThat(items).hasSize(2);
    assertThat(items)
        .allSatisfy(
            m -> {
              assertThat(m.get("strategy_id")).isEqualTo("s1");
              assertThat(m.get("kind")).isIn("EntryFilled", "PartialExitFilled");
            });
  }

  @Test
  void crossTenantRowsAreNeverReturned() {
    insert("other", "s1", "EntryFilled", "2026-05-14T14:00:00Z");
    insert("other", "s1", "PartialExitFilled", "2026-05-14T15:00:00Z");

    assertThat(reader.trades("dev", List.of("s1"), null, 100)).isEmpty();
  }

  private void insert(String tenant, String strategy, String kind, String occurredAtIso) {
    dsl.execute(
        "INSERT INTO audit_log (tenant_id, strategy_id, event_id, occurred_at, kind, subject)"
            + " VALUES (?, ?, ?, ?::timestamptz, ?, '{}'::jsonb)",
        tenant,
        strategy,
        UUID.randomUUID(),
        occurredAtIso,
        kind);
  }

  // --- weighted entry basis (#870) -------------------------------------------------------------
  // These exercise the arithmetic that makes the strip's P&L right: the basis is a
  // QUANTITY-WEIGHTED
  // average across the entry fill AND any later entry growth, not the first fill's price.

  @Test
  void entryBasisIsQuantityWeightedAcrossEntryGrowth() {
    // The live prod_real SPY shape: 10 at 2.76, then growth of 22 at a different price. A
    // first-fill basis would say 2.76 and mis-value every exit on the position.
    insertPriced(
        "dev", "s1", "EntryFilled", "2026-05-14T14:00:00Z", "sig-a", "filled_qty", 10, "2.76");
    insertPriced(
        "dev",
        "s1",
        "PositionEntryIncreased",
        "2026-05-14T14:05:00Z",
        "sig-a",
        "qty_added",
        22,
        "1.28");
    insertPriced(
        "dev", "s1", "PartialExitFilled", "2026-05-14T15:00:00Z", "sig-a", "qty_filled", 5, "2.77");

    Object basis = basisOf(reader.trades("dev", List.of("s1"), null, 100));

    // (10 x 2.76 + 22 x 1.28) / 32 = 1.7425 — the blended number, not 2.76.
    assertThat(new BigDecimal(String.valueOf(basis))).isEqualByComparingTo("1.7425");
  }

  @Test
  void anEntryFillWithNoPriceContributesToNeitherSideOfTheAverage() {
    // A priced 10 at 2.00 plus an unpriced 90 must stay 2.00, not collapse toward zero: counting
    // quantity we cannot value would dilute the basis and overstate every exit's P&L.
    insertPriced(
        "dev", "s1", "EntryFilled", "2026-05-14T14:00:00Z", "sig-b", "filled_qty", 10, "2.00");
    insertUnpriced(
        "dev", "s1", "PositionEntryIncreased", "2026-05-14T14:05:00Z", "sig-b", "qty_added", 90);
    insertPriced(
        "dev", "s1", "PartialExitFilled", "2026-05-14T15:00:00Z", "sig-b", "qty_filled", 1, "3.00");

    Object basis = basisOf(reader.trades("dev", List.of("s1"), null, 100));

    assertThat(new BigDecimal(String.valueOf(basis))).isEqualByComparingTo("2.00");
  }

  @Test
  void anExitWhoseEntryIsNotInTheTrailGetsNoBasis() {
    // Entry predates retention (or was lost): the row must still return, with a null basis, so the
    // strip renders the fill without a P&L rather than inventing one.
    insertPriced(
        "dev",
        "s1",
        "PartialExitFilled",
        "2026-05-14T15:00:00Z",
        "sig-orphan",
        "qty_filled",
        3,
        "4.25");

    List<Map<String, Object>> items = reader.trades("dev", List.of("s1"), null, 100);

    assertThat(items).hasSize(1);
    assertThat(items.get(0)).containsKey("entry_basis");
    assertThat(items.get(0).get("entry_basis")).isNull();
  }

  @Test
  void anotherTenantsEntryNeverSuppliesTheBasis() {
    // Same signal id under a different tenant. The basis query is tenant-scoped; if it were not,
    // one tenant's cost basis would value another tenant's exits.
    insertPriced(
        "other", "s1", "EntryFilled", "2026-05-14T14:00:00Z", "sig-x", "filled_qty", 10, "9.99");
    insertPriced(
        "dev", "s1", "PartialExitFilled", "2026-05-14T15:00:00Z", "sig-x", "qty_filled", 2, "4.00");

    List<Map<String, Object>> items = reader.trades("dev", List.of("s1"), null, 100);

    assertThat(items).hasSize(1);
    assertThat(items.get(0).get("entry_basis")).isNull();
  }

  /** The entry_basis attached to the single exit row in the result. */
  private static Object basisOf(List<Map<String, Object>> items) {
    return items.stream()
        .filter(m -> "PartialExitFilled".equals(m.get("kind")))
        .findFirst()
        .orElseThrow()
        .get("entry_basis");
  }

  private void insertPriced(
      String tenant,
      String strategy,
      String kind,
      String occurredAtIso,
      String correlationId,
      String qtyKey,
      long qty,
      String price) {
    dsl.execute(
        "INSERT INTO audit_log"
            + " (tenant_id, strategy_id, event_id, occurred_at, kind, correlation_id, subject)"
            + " VALUES (?, ?, ?, ?::timestamptz, ?, ?,"
            + "   jsonb_build_object(?, ?::bigint, 'avg_fill_price', ?::numeric))",
        tenant,
        strategy,
        UUID.randomUUID(),
        occurredAtIso,
        kind,
        correlationId,
        qtyKey,
        qty,
        new BigDecimal(price));
  }

  private void insertUnpriced(
      String tenant,
      String strategy,
      String kind,
      String occurredAtIso,
      String correlationId,
      String qtyKey,
      long qty) {
    dsl.execute(
        "INSERT INTO audit_log"
            + " (tenant_id, strategy_id, event_id, occurred_at, kind, correlation_id, subject)"
            + " VALUES (?, ?, ?, ?::timestamptz, ?, ?, jsonb_build_object(?, ?::bigint))",
        tenant,
        strategy,
        UUID.randomUUID(),
        occurredAtIso,
        kind,
        correlationId,
        qtyKey,
        qty);
  }
}
