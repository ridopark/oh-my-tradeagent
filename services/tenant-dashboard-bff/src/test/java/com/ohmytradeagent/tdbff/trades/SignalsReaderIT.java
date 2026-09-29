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
 * SQL-level coverage for {@link SignalsReader}; mirrors {@link TradesReaderIT}. The load-bearing
 * assertions are TENANT ISOLATION (this feed drives a BUY box, so another tenant's contract must
 * never be offered) and that {@code subject} comes back as a STRING — the dashboard parses it as
 * JSON text, and an object would silently empty the picker. Gated on {@code RUN_DB_ITS=true}.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class SignalsReaderIT {

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  private static Connection conn;
  private static DSLContext dsl;
  private SignalsReader reader;

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
    reader = new SignalsReader(dsl);
  }

  @Test
  void scopesToRequestedTenantAndStrategyAndAcceptedSignalsOnly() {
    insert("dev", "s1", "SignalAccepted", "2026-05-14T14:00:00Z");
    insert("other", "s1", "SignalAccepted", "2026-05-14T14:00:00Z"); // different tenant
    insert("dev", "s2", "SignalAccepted", "2026-05-14T14:00:00Z"); // strategy not requested
    insert("dev", "s1", "SignalReceived", "2026-05-14T14:00:00Z"); // carries no option_symbol
    insert("dev", "s1", "EntryFilled", "2026-05-14T14:00:00Z"); // a fill, not a signal

    List<Map<String, Object>> items = reader.signals("dev", List.of("s1"), 100);

    assertThat(items).hasSize(1);
    assertThat(items.get(0).get("strategy_id")).isEqualTo("s1");
  }

  @Test
  void crossTenantRowsAreNeverReturned() {
    insert("other", "s1", "SignalAccepted", "2026-05-14T14:00:00Z");

    assertThat(reader.signals("dev", List.of("s1"), 100)).isEmpty();
  }

  @Test
  void newestFirstAndSubjectIsAString() {
    insert("dev", "s1", "SignalAccepted", "2026-05-14T14:00:00Z");
    insert("dev", "s1", "SignalAccepted", "2026-05-14T16:00:00Z");

    List<Map<String, Object>> items = reader.signals("dev", List.of("s1"), 100);

    assertThat(items).hasSize(2);
    assertThat(items.get(0).get("occurred_at").toString())
        .as("newest first")
        .startsWith("2026-05-14T16:00");
    assertThat(items.get(0).get("subject"))
        .as("the dashboard JSON.parses this; an object here would empty the picker")
        .isInstanceOf(String.class);
  }

  @Test
  void noStrategiesReturnsEmptyRatherThanEveryTenant() {
    insert("dev", "s1", "SignalAccepted", "2026-05-14T14:00:00Z");

    assertThat(reader.signals("dev", List.of(), 100)).isEmpty();
  }

  private void insert(String tenant, String strategy, String kind, String occurredAtIso) {
    dsl.execute(
        "INSERT INTO audit_log (tenant_id, strategy_id, event_id, occurred_at, kind, subject)"
            + " VALUES (?, ?, ?, ?::timestamptz, ?,"
            + " '{\"option_symbol\": \"INTC  261009C00125000\"}'::jsonb)",
        tenant,
        strategy,
        UUID.randomUUID(),
        occurredAtIso,
        kind);
  }
}
