package com.ohmytradeagent.orchestrator.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.table;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ohmytradeagent.contract.StrategyConfig;
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
 * Phase A: {@link DbStrategyRegistry#list()} must return exactly the distinct {@code (tenant_id,
 * strategy_id)} set in {@code strategy_config} — the Phase-0 decided enumeration source (no {@code
 * tenants} table). Mirrors {@link StrategyConfigStoreIT}: a plain {@code DSL.using(conn)} over a
 * Testcontainers Postgres with Flyway-applied migrations, gated on {@code RUN_DB_ITS=true} so a
 * Docker-less {@code mvn test} skips it cleanly.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class DbStrategyRegistryListTest {

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

  /** Two tenants × strategies → list() returns exactly the distinct set. */
  @Test
  void listReturnsExactlyTheDistinctTenantStrategySet() throws Exception {
    insertRow("acme", "strat-a");
    insertRow("acme", "strat-b");
    insertRow("beta", "strat-a");

    DbStrategyRegistry registry = new DbStrategyRegistry(dsl, objectMapper);

    assertThat(registry.list())
        .containsExactlyInAnyOrder(
            new TenantStrategy("acme", "strat-a"),
            new TenantStrategy("acme", "strat-b"),
            new TenantStrategy("beta", "strat-a"));
  }

  /** A newly inserted row (simulating the UI write) is enumerated by list() with no restart. */
  @Test
  void newlyInsertedRowIsEnumeratedByListWithoutRestart() throws Exception {
    insertRow("acme", "strat-a");
    DbStrategyRegistry registry = new DbStrategyRegistry(dsl, objectMapper);
    assertThat(registry.list()).containsExactly(new TenantStrategy("acme", "strat-a"));

    // Simulate the Phase-I UI write at runtime against the SAME registry instance.
    insertRow("beta", "strat-new");

    assertThat(registry.list())
        .as("a runtime-inserted row must appear without reconstructing the registry")
        .containsExactlyInAnyOrder(
            new TenantStrategy("acme", "strat-a"), new TenantStrategy("beta", "strat-new"));
  }

  /** Empty table → empty list (not null, no throw). */
  @Test
  void emptyTableYieldsEmptyList() {
    DbStrategyRegistry registry = new DbStrategyRegistry(dsl, objectMapper);
    assertThat(registry.list()).isEmpty();
  }

  // --- helpers ---

  private void insertRow(String tenantId, String strategyId) throws Exception {
    StrategyConfig cfg = new StrategyConfig();
    cfg.setSchemaVersion(1L);
    cfg.setTenantId(tenantId);
    cfg.setStrategyId(strategyId);
    cfg.setBrokerTarget(StrategyConfig.BrokerTarget.PAPER);
    String json = objectMapper.writeValueAsString(cfg);
    dsl.execute(
        "INSERT INTO strategy_config "
            + "(tenant_id, strategy_id, schema_version, config, updated_by) "
            + "VALUES (?, ?, ?, ?::jsonb, ?)",
        tenantId,
        strategyId,
        1,
        json,
        "test");
  }
}
