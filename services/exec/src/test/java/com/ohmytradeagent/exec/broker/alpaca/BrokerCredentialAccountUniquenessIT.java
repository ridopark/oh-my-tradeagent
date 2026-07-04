package com.ohmytradeagent.exec.broker.alpaca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
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
 * Testcontainers Postgres + Flyway IT that pins the V6 partial unique index {@code
 * broker_credentials_provider_account_uk} DIRECTLY (raw inserts, bypassing the writer/crypto) — the
 * race-proof, fail-closed authority behind R-6.5. Proves: two rows sharing a non-blank {@code
 * (provider, expected_account_id)} but a different {@code tenant_id} collide at the index; blank
 * ({@code ''}) and NULL account rows are UNCONSTRAINED so multiple paper rows coexist; and a
 * distinct account (or a distinct provider) is permitted. Gated on {@code RUN_DB_ITS=true} (mirrors
 * {@code BrokerCredentialWriterIT}).
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class BrokerCredentialAccountUniquenessIT {

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  private static java.sql.Connection conn;
  private static DSLContext dsl;

  @BeforeAll
  static void initDb() throws Exception {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/exec")
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
  void clean() {
    dsl.deleteFrom(table("broker_credentials")).execute();
  }

  /** Raw insert of the NOT-NULL envelope columns with a chosen {@code expected_account_id}. */
  private void insertRow(String tenant, String provider, String account) {
    dsl.execute(
        "INSERT INTO broker_credentials "
            + "(tenant_id, provider, ciphertext, iv, wrapped_dek, dek_iv, kek_version, base_url,"
            + " expected_account_id, updated_by) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        tenant,
        provider,
        new byte[] {1},
        new byte[] {2},
        new byte[] {3},
        new byte[] {4},
        1,
        "https://x",
        account,
        "tester");
  }

  @Test
  void twoTenantsSameNonBlankAccount_secondInsertViolatesIndex() {
    insertRow("alice", "alpaca", "847309116");

    assertThatThrownBy(() -> insertRow("bob", "alpaca", "847309116"))
        .isInstanceOf(DataAccessException.class);

    // Fail-closed at the DB: only alice's row survives the collision.
    assertThat(rowCount("alice")).isEqualTo(1);
    assertThat(rowCount("bob")).isZero();
  }

  @Test
  void multipleBlankAccountRows_coexist() {
    // The partial index WHERE-clause excludes blank accounts, so paper rows are unconstrained.
    insertRow("alice", "alpaca", "");
    insertRow("bob", "alpaca", "");

    assertThat(rowCount("alice")).isEqualTo(1);
    assertThat(rowCount("bob")).isEqualTo(1);
  }

  @Test
  void multipleNullAccountRows_coexist() {
    // NULL accounts are likewise excluded from the index → multiple coexist.
    insertRow("alice", "alpaca", null);
    insertRow("bob", "alpaca", null);

    assertThat(rowCount("alice")).isEqualTo(1);
    assertThat(rowCount("bob")).isEqualTo(1);
  }

  @Test
  void distinctNonBlankAccounts_bothInsert() {
    insertRow("alice", "alpaca", "111111111");
    insertRow("bob", "alpaca", "222222222");

    assertThat(rowCount("alice")).isEqualTo(1);
    assertThat(rowCount("bob")).isEqualTo(1);
  }

  @Test
  void sameNonBlankAccountDifferentProvider_bothInsert() {
    // The index is keyed on (provider, expected_account_id) — the same account under a different
    // provider is a different key and permitted.
    insertRow("alice", "alpaca", "847309116");
    insertRow("bob", "etrade", "847309116");

    assertThat(rowCount("alice")).isEqualTo(1);
    assertThat(rowCount("bob")).isEqualTo(1);
  }

  private int rowCount(String tenant) {
    return dsl.selectCount()
        .from(table("broker_credentials"))
        .where(field("tenant_id").eq(tenant))
        .fetchOne(0, Integer.class);
  }
}
