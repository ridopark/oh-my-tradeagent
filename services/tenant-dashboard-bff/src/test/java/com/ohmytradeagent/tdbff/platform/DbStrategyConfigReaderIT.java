package com.ohmytradeagent.tdbff.platform;

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
 * SQL-level coverage for {@link DbStrategyConfigReader} and {@link TenantStrategyResolver} against
 * a real Postgres — both read the orchestrator-owned {@code strategy_config} table. The key
 * assertions are FAIL-SOFT (missing row / missing key → null, never a throw) and TENANT ISOLATION
 * (the resolver returns only the requested tenant's strategy ids). Gated on {@code
 * RUN_DB_ITS=true}; the {@code strategy_config} DDL is inlined (the BFF does not own that schema).
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class DbStrategyConfigReaderIT {

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  private static Connection conn;
  private static DSLContext dsl;

  private DbStrategyConfigReader reader;
  private TenantStrategyResolver resolver;

  @BeforeAll
  static void initDb() throws Exception {
    conn =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    dsl = DSL.using(conn, SQLDialect.POSTGRES);
    dsl.execute(
        "CREATE TABLE strategy_config ("
            + "  tenant_id VARCHAR(64) NOT NULL,"
            + "  strategy_id VARCHAR(64) NOT NULL,"
            + "  schema_version INTEGER NOT NULL,"
            + "  config JSONB NOT NULL,"
            + "  version BIGINT NOT NULL DEFAULT 1,"
            + "  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),"
            + "  updated_by VARCHAR(128) NOT NULL,"
            + "  PRIMARY KEY (tenant_id, strategy_id))");
  }

  @AfterAll
  static void closeDb() throws Exception {
    if (conn != null) {
      conn.close();
    }
  }

  @BeforeEach
  void reset() {
    dsl.execute("DELETE FROM strategy_config");
    reader = new DbStrategyConfigReader(dsl);
    resolver = new TenantStrategyResolver(dsl);
  }

  @Test
  void brokerTargetReadsTheScalarFromConfig() {
    insert("acme", "s1", "{\"broker_target\":\"alpaca-paper\"}");

    assertThat(reader.brokerTarget("acme", "s1")).isEqualTo("alpaca-paper");
  }

  @Test
  void brokerTargetForMissingRowIsNull_notAThrow() {
    assertThat(reader.brokerTarget("nobody", "nope")).isNull();
  }

  @Test
  void brokerTargetWhenConfigHasNoBrokerTargetKeyIsNull() {
    insert("acme", "s1", "{}");

    assertThat(reader.brokerTarget("acme", "s1")).isNull();
  }

  @Test
  void listAllEnumeratesEveryStrategyWithBrokerTargetOrdered() {
    insert("acme", "beta", "{\"broker_target\":\"alpaca-live\"}");
    insert("acme", "alpha", "{\"broker_target\":\"alpaca-paper\"}");
    insert("zeta", "gamma", "{}"); // no broker_target → null, never thrown

    assertThat(reader.listAll())
        .extracting(
            DbStrategyConfigReader.TenantStrategyBrokerTarget::tenantId,
            DbStrategyConfigReader.TenantStrategyBrokerTarget::strategyId,
            DbStrategyConfigReader.TenantStrategyBrokerTarget::brokerTarget)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("acme", "alpha", "alpaca-paper"),
            org.assertj.core.groups.Tuple.tuple("acme", "beta", "alpaca-live"),
            org.assertj.core.groups.Tuple.tuple("zeta", "gamma", null));
  }

  @Test
  void resolverReturnsOnlyTheTenantsIdsOrderedAscending() {
    insert("acme", "beta", "{}");
    insert("acme", "alpha", "{}");
    insert("other", "gamma", "{}");

    assertThat(resolver.strategyIdsForTenant("acme")).containsExactly("alpha", "beta");
  }

  @Test
  void resolverForUnknownTenantIsEmpty() {
    assertThat(resolver.strategyIdsForTenant("ghost")).isEmpty();
  }

  private static void insert(String tenantId, String strategyId, String configJson) {
    dsl.execute(
        "INSERT INTO strategy_config (tenant_id, strategy_id, schema_version, config, updated_by)"
            + " VALUES (?, ?, 1, ?::jsonb, 'test')",
        tenantId,
        strategyId,
        configJson);
  }
}
