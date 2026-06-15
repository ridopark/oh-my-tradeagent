package com.ohmytradeagent.tdbff.credentials;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
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
 * SQL-level coverage for {@link BrokerCredentialStatusReader} against a real Postgres. The key
 * assertions are TENANT ISOLATION ({@code tenant_id = ?} never returns another tenant's row) and
 * the SECRET ALLOWLIST: the returned maps carry only non-secret fields and contain no secret column
 * key. Gated on {@code RUN_DB_ITS=true}; the {@code broker_credentials} DDL is inlined (the BFF
 * does not own that schema). Testcontainers runs the SELECT as superuser, so no GRANT is required
 * here.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class BrokerCredentialStatusReaderIT {

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  private static Connection conn;
  private static DSLContext dsl;

  private BrokerCredentialStatusReader reader;

  @BeforeAll
  static void initDb() throws Exception {
    conn =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    dsl = DSL.using(conn, SQLDialect.POSTGRES);
    // Mirrors services/exec/.../db/exec/V5__broker_credentials.sql.
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
            + "  ws_url VARCHAR(255),"
            + "  expected_account_id VARCHAR(64),"
            + "  version BIGINT NOT NULL DEFAULT 1,"
            + "  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),"
            + "  updated_by VARCHAR(128) NOT NULL,"
            + "  PRIMARY KEY (tenant_id, provider))");
  }

  @AfterAll
  static void closeDb() throws Exception {
    if (conn != null) {
      conn.close();
    }
  }

  @BeforeEach
  void reset() {
    dsl.execute("DELETE FROM broker_credentials");
    reader = new BrokerCredentialStatusReader(dsl);
  }

  @Test
  void returnsNonSecretStatus_scopedToTenant() {
    insert("acme", "alpaca-paper", 7L, "PA3FKGPFYPLH", "ops@acme");
    insert(
        "other", "alpaca-paper", 3L, "PALEAKLEAK", "ops@other"); // different tenant — must not leak

    List<Map<String, Object>> items = reader.statuses("acme");

    assertThat(items).hasSize(1);
    Map<String, Object> row = items.get(0);
    assertThat(row.get("provider")).isEqualTo("alpaca-paper");
    assertThat(row.get("configured")).isEqualTo(true);
    assertThat(row.get("version")).isEqualTo(7L);
    assertThat(row.get("broker_account_id")).isEqualTo("PA3FKGPFYPLH");
    assertThat(row.get("updated_by")).isEqualTo("ops@acme");
    assertThat(row)
        .containsOnlyKeys(
            "provider", "configured", "version", "broker_account_id", "updated_at", "updated_by");
    // Hard allowlist guard: no secret column ever appears as a map key.
    assertThat(row.keySet())
        .doesNotContain("ciphertext", "iv", "wrapped_dek", "dek_iv", "kek_version");
  }

  @Test
  void ordersByProvider() {
    insert("acme", "ibkr", 1L, "U111", "ops@acme");
    insert("acme", "alpaca-paper", 1L, "PA222", "ops@acme");

    List<Map<String, Object>> items = reader.statuses("acme");

    assertThat(items).extracting(m -> m.get("provider")).containsExactly("alpaca-paper", "ibkr");
  }

  private void insert(
      String tenant, String provider, long version, String account, String updatedBy) {
    dsl.execute(
        "INSERT INTO broker_credentials (tenant_id, provider, ciphertext, iv, wrapped_dek, dek_iv,"
            + " kek_version, base_url, ws_url, expected_account_id, version, updated_by)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        tenant,
        provider,
        new byte[] {1, 2, 3},
        new byte[] {4, 5, 6},
        new byte[] {7, 8, 9},
        new byte[] {10, 11, 12},
        1,
        "https://paper-api.example.com",
        "wss://stream.example.com",
        account,
        version,
        updatedBy);
  }
}
