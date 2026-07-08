package com.ohmytradeagent.orchestrator.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.jooq.impl.DSL.table;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
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
 * account-loss-cap-db epic (Phase 1) IT for {@link DbTenantRegistry} over the {@code tenant_config}
 * store (V8). Mirrors {@code StrategyConfigStoreIT}: a plain {@code DSL.using(conn)} over a
 * Testcontainers Postgres with Flyway-applied migrations, gated on {@code RUN_DB_ITS=true} so a
 * Docker-less {@code mvn test} skips it cleanly.
 *
 * <p>Asserts: (1) a seeded cap row round-trips through {@link DbTenantRegistry} with {@code
 * version} defaulting to 1; (2) a missing row returns a default {@link TenantConfig} (null
 * threshold => cap inert), NOT a throw — matching {@link YamlTenantRegistry}'s missing-file
 * semantics so the reader swap is transparent.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class DbTenantRegistryIT {

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  private static Connection conn;
  private static DSLContext dsl;

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
    dsl.deleteFrom(table("tenant_config")).execute();
  }

  /** A seeded pct cap row reads back through DbTenantRegistry with version defaulting to 1. */
  @Test
  void seededRow_readsBackThroughDbTenantRegistry() {
    dsl.execute(
        "INSERT INTO tenant_config "
            + "(tenant_id, account_daily_loss_threshold, account_daily_loss_pct, updated_by) "
            + "VALUES (?, ?, ?, ?)",
        "t",
        null,
        new BigDecimal("0.40"),
        "test");

    TenantConfig cfg = new DbTenantRegistry(dsl).get("t");
    assertThat(cfg.getAccountDailyLossPct()).isEqualByComparingTo(new BigDecimal("0.40"));
    assertThat(cfg.getAccountDailyLossThreshold()).isNull();

    Long version =
        dsl.fetchOne("SELECT version FROM tenant_config WHERE tenant_id = ?", "t")
            .get("version", Long.class);
    assertThat(version).as("version column must default to 1").isEqualTo(1L);
  }

  /** Both cap columns round-trip when both are set. */
  @Test
  void seededRow_bothCaps_readBack() {
    dsl.execute(
        "INSERT INTO tenant_config "
            + "(tenant_id, account_daily_loss_threshold, account_daily_loss_pct, updated_by) "
            + "VALUES (?, ?, ?, ?)",
        "t",
        new BigDecimal("5000"),
        new BigDecimal("0.40"),
        "test");

    TenantConfig cfg = new DbTenantRegistry(dsl).get("t");
    assertThat(cfg.getAccountDailyLossThreshold()).isEqualByComparingTo(new BigDecimal("5000"));
    assertThat(cfg.getAccountDailyLossPct()).isEqualByComparingTo(new BigDecimal("0.40"));
  }

  /**
   * Missing row => default TenantConfig (null threshold), no throw (matches YamlTenantRegistry).
   */
  @Test
  void missingRow_returnsDefaultConfig_noThrow() {
    DbTenantRegistry registry = new DbTenantRegistry(dsl);
    assertThatCode(
            () -> {
              TenantConfig cfg = registry.get("does-not-exist");
              assertThat(cfg.getAccountDailyLossThreshold()).isNull();
              assertThat(cfg.getAccountDailyLossPct()).isNull();
            })
        .doesNotThrowAnyException();
  }
}
