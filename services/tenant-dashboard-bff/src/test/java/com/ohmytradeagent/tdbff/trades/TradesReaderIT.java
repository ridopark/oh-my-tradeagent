package com.ohmytradeagent.tdbff.trades;

import static org.assertj.core.api.Assertions.assertThat;

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
}
