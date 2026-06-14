package com.ohmytradeagent.orchestrator.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.table;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.bootstrap.StrategyConfigSeedReconciler;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * P0a IT for the strategy-config DB store (V5), {@link DbStrategyRegistry}, and {@link
 * StrategyConfigSeedReconciler}. Mirrors {@code ContractActivitiesImplIT}: a plain {@code
 * DSL.using(conn)} over a Testcontainers Postgres with Flyway-applied migrations, gated on {@code
 * RUN_DB_ITS=true} so a Docker-less {@code mvn test} skips it cleanly.
 *
 * <p>Asserts: (1) V5 applies and a JSONB-stored StrategyConfig round-trips through {@link
 * DbStrategyRegistry} byte-for-byte with {@code version} defaulting to 1; (2) a missing row throws
 * the same not-found type as {@link YamlStrategyRegistry}; (3) a row with a newer-than-build {@code
 * schema_version} fails closed; (4) the seed reconciler seeds an absent (tenant, strategy), is
 * idempotent on re-run, and never overwrites a pre-existing row.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class StrategyConfigStoreIT {

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  private static Connection conn;
  private static DSLContext dsl;
  private static ObjectMapper objectMapper;

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
    // Match the production ObjectMapper shape (Spring Boot auto-config registers JavaTimeModule).
    objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
  }

  @AfterAll
  static void closeDb() throws Exception {
    if (conn != null) conn.close();
  }

  @BeforeEach
  void truncate() {
    dsl.deleteFrom(table("strategy_config")).execute();
  }

  /**
   * Insert a StrategyConfig as a JSONB row, read it back via DbStrategyRegistry → equal original.
   */
  @Test
  void v5JsonbRoundTripsThroughDbStrategyRegistryWithVersionDefaultingTo1() throws Exception {
    StrategyConfig original = sampleConfig();
    insertRow("dev", "copytrade-v1", 1, original);

    DbStrategyRegistry registry = new DbStrategyRegistry(dsl, objectMapper);
    StrategyConfig roundTripped = registry.get("dev", "copytrade-v1");

    assertThat(roundTripped).isEqualTo(original);

    Long version =
        dsl.fetchOne(
                "SELECT version FROM strategy_config WHERE tenant_id = ? AND strategy_id = ?",
                "dev",
                "copytrade-v1")
            .get("version", Long.class);
    assertThat(version).as("version column must default to 1").isEqualTo(1L);
  }

  /** Missing row → same not-found behavior as YamlStrategyRegistry. */
  @Test
  void missingRowThrowsStrategyNotFound() {
    DbStrategyRegistry registry = new DbStrategyRegistry(dsl, objectMapper);
    assertThatThrownBy(() -> registry.get("dev", "does-not-exist"))
        .isInstanceOf(YamlStrategyRegistry.StrategyNotFoundException.class);
  }

  /** A newer-than-build schema_version fails closed rather than parsing the row. */
  @Test
  void newerSchemaVersionFailsClosed() throws Exception {
    StrategyConfig original = sampleConfig();
    long tooNew = DbStrategyRegistry.MAX_SUPPORTED_SCHEMA_VERSION + 1;
    insertRow("dev", "future-strat", (int) tooNew, original);

    DbStrategyRegistry registry = new DbStrategyRegistry(dsl, objectMapper);
    assertThatThrownBy(() -> registry.get("dev", "future-strat"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exceeds build-supported");
  }

  /**
   * Seed an absent (tenant, strategy) from a synthetic tree; idempotent on re-run; no overwrite.
   */
  @Test
  void seedReconcilerSeedsThenIsIdempotentAndNonDestructive(
      @org.junit.jupiter.api.io.TempDir Path tenantsDir) throws Exception {
    // Synthetic tenants tree: tenants/acme/strategies/strat-a.yaml
    Path strategiesDir = tenantsDir.resolve("acme").resolve("strategies");
    Files.createDirectories(strategiesDir);
    Files.writeString(
        strategiesDir.resolve("strat-a.yaml"),
        "schema_version: 1\n"
            + "tenant_id: acme\n"
            + "strategy_id: strat-a\n"
            + "broker_target: paper\n");

    YamlStrategyRegistry yaml = new YamlStrategyRegistry(tenantsDir.toString());
    StrategyConfigSeedReconciler reconciler =
        new StrategyConfigSeedReconciler(tenantsDir.toString(), yaml, dsl, objectMapper);
    ApplicationArguments noArgs = new org.springframework.boot.DefaultApplicationArguments();

    // First run → seeds exactly one row.
    reconciler.run(noArgs);
    assertThat(rowCount()).as("first seed run inserts one row").isEqualTo(1);

    // Capture the seeded blob + version so we can prove the second run leaves it untouched.
    String configAfterFirst = configText("acme", "strat-a");
    Long versionAfterFirst = versionOf("acme", "strat-a");

    // Second run → still exactly one row, unchanged (idempotent / non-destructive).
    reconciler.run(noArgs);
    assertThat(rowCount()).as("re-run must not duplicate").isEqualTo(1);
    assertThat(configText("acme", "strat-a")).isEqualTo(configAfterFirst);
    assertThat(versionOf("acme", "strat-a")).isEqualTo(versionAfterFirst);
  }

  /** A pre-existing row with DIFFERENT content is NOT overwritten by the seeder. */
  @Test
  void seedReconcilerLeavesPreexistingRowUntouched(
      @org.junit.jupiter.api.io.TempDir Path tenantsDir) throws Exception {
    Path strategiesDir = tenantsDir.resolve("acme").resolve("strategies");
    Files.createDirectories(strategiesDir);
    Files.writeString(
        strategiesDir.resolve("strat-a.yaml"),
        "schema_version: 1\n"
            + "tenant_id: acme\n"
            + "strategy_id: strat-a\n"
            + "broker_target: paper\n");

    // Pre-existing row with content that differs from the YAML (max_positions=99, version bumped).
    dsl.execute(
        "INSERT INTO strategy_config "
            + "(tenant_id, strategy_id, schema_version, config, version, updated_by) "
            + "VALUES (?, ?, ?, ?::jsonb, ?, ?)",
        "acme",
        "strat-a",
        1,
        "{\"schema_version\":1,\"tenant_id\":\"acme\",\"strategy_id\":\"strat-a\","
            + "\"broker_target\":\"paper\",\"max_positions\":99}",
        7L,
        "operator:manual");
    String preexistingConfig = configText("acme", "strat-a");

    YamlStrategyRegistry yaml = new YamlStrategyRegistry(tenantsDir.toString());
    StrategyConfigSeedReconciler reconciler =
        new StrategyConfigSeedReconciler(tenantsDir.toString(), yaml, dsl, objectMapper);
    reconciler.run(new org.springframework.boot.DefaultApplicationArguments());

    assertThat(rowCount()).isEqualTo(1);
    assertThat(configText("acme", "strat-a"))
        .as("pre-existing row content must be preserved (no overwrite)")
        .isEqualTo(preexistingConfig);
    assertThat(versionOf("acme", "strat-a")).as("pre-existing version preserved").isEqualTo(7L);
  }

  // --- helpers ---

  private static StrategyConfig sampleConfig() {
    StrategyConfig c = new StrategyConfig();
    c.setSchemaVersion(1L);
    c.setTenantId("dev");
    c.setStrategyId("copytrade-v1");
    c.setBrokerTarget(StrategyConfig.BrokerTarget.PAPER);
    c.setMaxPositions(5L);
    c.setMinContracts(1L);
    c.setMaxContracts(5L);
    return c;
  }

  private void insertRow(String tenantId, String strategyId, int schemaVersion, StrategyConfig cfg)
      throws Exception {
    String json = objectMapper.writeValueAsString(cfg);
    dsl.execute(
        "INSERT INTO strategy_config "
            + "(tenant_id, strategy_id, schema_version, config, updated_by) "
            + "VALUES (?, ?, ?, ?::jsonb, ?)",
        tenantId,
        strategyId,
        schemaVersion,
        json,
        "test");
  }

  private int rowCount() {
    return dsl.select(count()).from(table("strategy_config")).fetchOneInto(int.class);
  }

  private String configText(String tenantId, String strategyId) {
    return dsl.fetchOne(
            "SELECT config::text AS config_text FROM strategy_config "
                + "WHERE tenant_id = ? AND strategy_id = ?",
            tenantId,
            strategyId)
        .get("config_text", String.class);
  }

  private Long versionOf(String tenantId, String strategyId) {
    return dsl.fetchOne(
            "SELECT version FROM strategy_config WHERE tenant_id = ? AND strategy_id = ?",
            tenantId,
            strategyId)
        .get("version", Long.class);
  }
}
