package com.ohmytradeagent.orchestrator.activities;

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
 * PLAN-2026-07-22: {@link DbTenantStrategies} enumerates a tenant's strategies from {@code
 * strategy_config} with a parameterized {@code WHERE tenant_id = ?}, so a DB-onboarded tenant
 * absent from the {@code tenants/} ConfigMap tree (prod-kipark, 2026-07-21) still resolves — and
 * cross- tenant isolation holds (tenant A never sees tenant B's strategies). Mirrors {@link
 * DbStrategyRegistryListTest}: a plain {@code DSL.using(conn)} over a Testcontainers Postgres with
 * Flyway-applied migrations, gated on {@code RUN_DB_ITS=true} so a Docker-less {@code mvn test}
 * skips it cleanly.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class DbTenantStrategiesTest {

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

  /**
   * The prod-kipark scenario: a tenant present ONLY in the DB (no tenants-tree entry) resolves its
   * strategies. Cross-tenant isolation: tenant A's call returns ONLY A's strategies, never B's.
   */
  @Test
  void resolvesOnlyTheRequestingTenantsStrategies() throws Exception {
    insertRow("prod-kipark", "copytrade-v1", StrategyConfig.BrokerTarget.LIVE);
    insertRow("prod_real", "copytrade-v1", StrategyConfig.BrokerTarget.LIVE);
    insertRow("prod_real", "watchlist-v1", StrategyConfig.BrokerTarget.LIVE);

    DbTenantStrategies resolver = new DbTenantStrategies(dsl);

    // Hyphen tenant resolves its own strategy — never the underscore tenant's.
    assertThat(resolver.strategyIdsForTenant("prod-kipark")).containsExactly("copytrade-v1");
    // Underscore tenant resolves BOTH its strategies — never the hyphen tenant's.
    assertThat(resolver.strategyIdsForTenant("prod_real"))
        .containsExactly("copytrade-v1", "watchlist-v1");
  }

  /** A tenant absent from strategy_config yields an EMPTY list (not null, no throw). */
  @Test
  void absentTenantYieldsEmptyList() throws Exception {
    insertRow("prod_real", "copytrade-v1", StrategyConfig.BrokerTarget.LIVE);
    DbTenantStrategies resolver = new DbTenantStrategies(dsl);
    assertThat(resolver.strategyIdsForTenant("nobody")).isEmpty();
  }

  /** A runtime-inserted row is enumerated with no restart (fresh read each call). */
  @Test
  void newlyInsertedRowIsEnumeratedWithoutRestart() throws Exception {
    DbTenantStrategies resolver = new DbTenantStrategies(dsl);
    assertThat(resolver.strategyIdsForTenant("prod_real")).isEmpty();

    insertRow("prod_real", "copytrade-v1", StrategyConfig.BrokerTarget.LIVE);

    assertThat(resolver.strategyIdsForTenant("prod_real")).containsExactly("copytrade-v1");
  }

  // --- helpers ---

  private void insertRow(String tenantId, String strategyId, StrategyConfig.BrokerTarget target)
      throws Exception {
    StrategyConfig cfg = new StrategyConfig();
    cfg.setSchemaVersion(1L);
    cfg.setTenantId(tenantId);
    cfg.setStrategyId(strategyId);
    cfg.setBrokerTarget(target);
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
