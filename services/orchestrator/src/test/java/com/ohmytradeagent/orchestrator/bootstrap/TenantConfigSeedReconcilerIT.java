package com.ohmytradeagent.orchestrator.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.table;

import com.ohmytradeagent.orchestrator.platform.DbTenantRegistry;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * account-loss-cap-db epic (Phase 1) IT for {@link TenantConfigSeedReconciler}. Mirrors the seed
 * cases in {@code StrategyConfigStoreIT}: boots the reconciler against a Testcontainers Postgres
 * (Flyway-applied V8) with a synthetic tenants tree, gated on {@code RUN_DB_ITS=true}.
 *
 * <p>Asserts: (1) an empty table + a YAML fixture carrying {@code account_daily_loss_pct: 0.40}
 * seeds exactly one row whose cap reads back via {@link DbTenantRegistry}; (2) a second run leaves
 * that row untouched (idempotent / non-destructive).
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class TenantConfigSeedReconcilerIT {

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

  /** Synthetic tenants tree: a strategy file (so the tenant is enumerated) + a tenant.yaml cap. */
  private static void writeTenantFixture(Path tenantsDir, String tenant, String pct)
      throws Exception {
    Path strategiesDir = tenantsDir.resolve(tenant).resolve("strategies");
    Files.createDirectories(strategiesDir);
    Files.writeString(
        strategiesDir.resolve("strat-a.yaml"),
        "schema_version: 1\ntenant_id: "
            + tenant
            + "\nstrategy_id: strat-a\nbroker_target: paper\n");
    Files.writeString(
        tenantsDir.resolve(tenant).resolve("tenant.yaml"),
        "tenant_id: " + tenant + "\naccount_daily_loss_pct: " + pct + "\n");
  }

  @Test
  void seedsOneRowThenIsIdempotent(@org.junit.jupiter.api.io.TempDir Path tenantsDir)
      throws Exception {
    writeTenantFixture(tenantsDir, "acme", "0.40");

    TenantConfigSeedReconciler reconciler =
        new TenantConfigSeedReconciler(tenantsDir.toString(), dsl);
    ApplicationArguments noArgs = new DefaultApplicationArguments();

    // First run => seeds exactly one row with the YAML cap.
    reconciler.run(noArgs);
    assertThat(rowCount()).as("first seed run inserts one row").isEqualTo(1);
    assertThat(new DbTenantRegistry(dsl).get("acme").getAccountDailyLossPct())
        .isEqualByComparingTo(new BigDecimal("0.40"));

    Long versionAfterFirst = versionOf("acme");

    // Second run => still exactly one row, unchanged (idempotent / non-destructive).
    reconciler.run(noArgs);
    assertThat(rowCount()).as("re-run must not duplicate").isEqualTo(1);
    assertThat(versionOf("acme")).as("pre-existing version preserved").isEqualTo(versionAfterFirst);
    assertThat(new DbTenantRegistry(dsl).get("acme").getAccountDailyLossPct())
        .isEqualByComparingTo(new BigDecimal("0.40"));
  }

  /** A pre-existing row with a DIFFERENT (tighter) cap is NOT overwritten by the seeder. */
  @Test
  void leavesPreexistingRowUntouched(@org.junit.jupiter.api.io.TempDir Path tenantsDir)
      throws Exception {
    writeTenantFixture(tenantsDir, "acme", "0.40");

    // Operator already tightened the DB row to 0.20, version bumped.
    dsl.execute(
        "INSERT INTO tenant_config "
            + "(tenant_id, account_daily_loss_pct, version, updated_by) VALUES (?, ?, ?, ?)",
        "acme",
        new BigDecimal("0.20"),
        7L,
        "operator:manual");

    new TenantConfigSeedReconciler(tenantsDir.toString(), dsl)
        .run(new DefaultApplicationArguments());

    assertThat(rowCount()).isEqualTo(1);
    assertThat(new DbTenantRegistry(dsl).get("acme").getAccountDailyLossPct())
        .as("pre-existing cap must be preserved (no overwrite)")
        .isEqualByComparingTo(new BigDecimal("0.20"));
    assertThat(versionOf("acme")).as("pre-existing version preserved").isEqualTo(7L);
  }

  private int rowCount() {
    return dsl.select(count()).from(table("tenant_config")).fetchOneInto(int.class);
  }

  private Long versionOf(String tenantId) {
    return dsl.fetchOne("SELECT version FROM tenant_config WHERE tenant_id = ?", tenantId)
        .get("version", Long.class);
  }
}
