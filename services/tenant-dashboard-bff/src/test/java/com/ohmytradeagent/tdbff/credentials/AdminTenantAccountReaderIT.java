package com.ohmytradeagent.tdbff.credentials;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
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
 * SQL-level coverage for {@link AdminTenantAccountReader}: it routes a {@code *-live} broker_target
 * to the LIVE exec DB and everything else to PAPER, derives {@code provider} from the broker_target
 * prefix, and returns ONLY {@code expected_account_id} (never a secret column). Two separate
 * Postgres containers stand in for the paper and live exec DBs so a routing bug surfaces (a
 * mis-routed read returns the WRONG DB's account, or null). Gated on {@code RUN_DB_ITS=true}.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class AdminTenantAccountReaderIT {

  @Container static final PostgreSQLContainer<?> paperPg = new PostgreSQLContainer<>("postgres:16");

  @Container static final PostgreSQLContainer<?> livePg = new PostgreSQLContainer<>("postgres:16");

  private static Connection paperConn;
  private static Connection liveConn;
  private static DSLContext paperDsl;
  private static DSLContext liveDsl;

  private AdminTenantAccountReader reader;

  @BeforeAll
  static void initDb() throws Exception {
    paperConn =
        DriverManager.getConnection(
            paperPg.getJdbcUrl(), paperPg.getUsername(), paperPg.getPassword());
    liveConn =
        DriverManager.getConnection(
            livePg.getJdbcUrl(), livePg.getUsername(), livePg.getPassword());
    paperDsl = DSL.using(paperConn, SQLDialect.POSTGRES);
    liveDsl = DSL.using(liveConn, SQLDialect.POSTGRES);
    createTable(paperDsl);
    createTable(liveDsl);
  }

  // Mirrors services/exec/.../db/exec/V5__broker_credentials.sql (secret columns INCLUDED on
  // purpose
  // so the allowlist assertion is meaningful — the reader must not select them).
  private static void createTable(DSLContext dsl) {
    dsl.execute(
        "CREATE TABLE broker_credentials ("
            + "  tenant_id VARCHAR(64) NOT NULL,"
            + "  provider VARCHAR(32) NOT NULL,"
            + "  ciphertext BYTEA NOT NULL,"
            + "  iv BYTEA NOT NULL,"
            + "  wrapped_dek BYTEA NOT NULL,"
            + "  dek_iv BYTEA NOT NULL,"
            + "  kek_version INTEGER NOT NULL,"
            + "  base_url VARCHAR(255) NOT NULL,"
            + "  expected_account_id VARCHAR(64),"
            + "  version BIGINT NOT NULL DEFAULT 1,"
            + "  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),"
            + "  updated_by VARCHAR(128) NOT NULL,"
            + "  PRIMARY KEY (tenant_id, provider))");
  }

  @AfterAll
  static void closeDb() throws Exception {
    if (paperConn != null) paperConn.close();
    if (liveConn != null) liveConn.close();
  }

  @BeforeEach
  void reset() {
    paperDsl.execute("DELETE FROM broker_credentials");
    liveDsl.execute("DELETE FROM broker_credentials");
    reader = new AdminTenantAccountReader(paperDsl, liveDsl);
  }

  @Test
  void liveTargetReadsLiveDb() {
    insert(liveDsl, "acme", "alpaca", "847309116");
    insert(paperDsl, "acme", "alpaca", "PA000PAPER"); // must NOT be returned for a -live target

    assertThat(reader.accountId("acme", "alpaca-live")).isEqualTo("847309116");
  }

  @Test
  void paperTargetReadsPaperDb() {
    insert(paperDsl, "acme", "alpaca", "PA000PAPER");
    insert(liveDsl, "acme", "alpaca", "847309116"); // must NOT be returned for a -paper target

    assertThat(reader.accountId("acme", "alpaca-paper")).isEqualTo("PA000PAPER");
  }

  @Test
  void missingRowIsNull_notAThrow() {
    assertThat(reader.accountId("ghost", "alpaca-paper")).isNull();
  }

  @Test
  void nullBrokerTargetIsNull() {
    assertThat(reader.accountId("acme", null)).isNull();
  }

  private static void insert(DSLContext dsl, String tenant, String provider, String account) {
    dsl.execute(
        "INSERT INTO broker_credentials (tenant_id, provider, ciphertext, iv, wrapped_dek, dek_iv,"
            + " kek_version, base_url, expected_account_id, version, updated_by)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        tenant,
        provider,
        new byte[] {1},
        new byte[] {2},
        new byte[] {3},
        new byte[] {4},
        1,
        "https://example.com",
        account,
        1L,
        "test");
  }
}
