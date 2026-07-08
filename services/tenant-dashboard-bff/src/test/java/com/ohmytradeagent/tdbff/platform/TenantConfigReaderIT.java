package com.ohmytradeagent.tdbff.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
 * SQL-level coverage for {@link TenantConfigReader} against a real Postgres reading the
 * orchestrator-owned {@code tenant_config} table (V8). The key assertions are FAIL-SOFT (a missing
 * row → all-null {@link TenantConfigReader.TenantCap}, never a throw) and that the two NUMERIC caps
 * + {@code version} round-trip. Gated on {@code RUN_DB_ITS=true}; the {@code tenant_config} DDL is
 * inlined (the BFF does not own that schema, mirroring {@link DbStrategyConfigReaderIT}).
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class TenantConfigReaderIT {

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  private static Connection conn;
  private static DSLContext dsl;

  private TenantConfigReader reader;

  @BeforeAll
  static void initDb() throws Exception {
    conn =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    dsl = DSL.using(conn, SQLDialect.POSTGRES);
    // Inlined V8 shape (the BFF does not own the orchestrator schema).
    dsl.execute(
        "CREATE TABLE tenant_config ("
            + "  tenant_id VARCHAR(64) PRIMARY KEY,"
            + "  account_daily_loss_threshold NUMERIC,"
            + "  account_daily_loss_pct NUMERIC,"
            + "  version BIGINT NOT NULL DEFAULT 1,"
            + "  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),"
            + "  updated_by VARCHAR(128) NOT NULL)");
  }

  @AfterAll
  static void closeDb() throws Exception {
    if (conn != null) {
      conn.close();
    }
  }

  @BeforeEach
  void reset() {
    dsl.execute("DELETE FROM tenant_config");
    reader = new TenantConfigReader(dsl);
  }

  @Test
  void capForReadsThePctAndVersionFromTheSeededRow() {
    dsl.execute(
        "INSERT INTO tenant_config (tenant_id, account_daily_loss_pct, version, updated_by)"
            + " VALUES ('acme', 0.40, 7, 'seed:boot')");

    TenantConfigReader.TenantCap cap = reader.capFor("acme");

    assertThat(cap.accountDailyLossPct()).isEqualByComparingTo(new BigDecimal("0.40"));
    assertThat(cap.accountDailyLossThreshold()).isNull();
    assertThat(cap.version()).isEqualTo(7L);
  }

  @Test
  void capForReadsBothCapsWhenPresent() {
    dsl.execute(
        "INSERT INTO tenant_config (tenant_id, account_daily_loss_threshold,"
            + " account_daily_loss_pct, updated_by)"
            + " VALUES ('acme', 2500, 0.15, 'seed:boot')");

    TenantConfigReader.TenantCap cap = reader.capFor("acme");

    assertThat(cap.accountDailyLossThreshold()).isEqualByComparingTo(new BigDecimal("2500"));
    assertThat(cap.accountDailyLossPct()).isEqualByComparingTo(new BigDecimal("0.15"));
    assertThat(cap.version()).isEqualTo(1L);
  }

  @Test
  void capForMissingRowIsAllNull_notAThrow() {
    TenantConfigReader.TenantCap cap = reader.capFor("nobody");

    assertThat(cap.accountDailyLossThreshold()).isNull();
    assertThat(cap.accountDailyLossPct()).isNull();
    assertThat(cap.version()).isNull();
  }

  @Test
  void capForReadsOnlyTheRequestedTenant() {
    dsl.execute(
        "INSERT INTO tenant_config (tenant_id, account_daily_loss_pct, updated_by)"
            + " VALUES ('acme', 0.40, 'seed:boot')");
    dsl.execute(
        "INSERT INTO tenant_config (tenant_id, account_daily_loss_pct, updated_by)"
            + " VALUES ('other', 0.99, 'seed:boot')");

    assertThat(reader.capFor("acme").accountDailyLossPct())
        .isEqualByComparingTo(new BigDecimal("0.40"));
  }
}
