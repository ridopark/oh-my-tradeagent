package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Issue #88 Testcontainers IT: drives {@link TenantConfigChangedEmitter} through the production
 * {@link AuditActivitiesImpl} (with chain writer enabled) and asserts the {@code audit_log} row is
 * present and has its {@code row_hash} populated by the PR #117 chain writer. Mirrors the {@link
 * LivePromotionApprovalIT} shape so the same gating and container conventions apply.
 *
 * <p>Connects as the {@code orchestrator_runtime} role so the V3 immutability REVOKE binding is
 * exercised end-to-end. The {@code row_hash} assertion (non-NULL, 32 bytes) proves the emit path is
 * routed through {@link AuditActivities#log} and the chain writer is not bypassed — halt- condition
 * #1.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class TenantConfigChangedIT {

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  private static final String RUNTIME_PASSWORD = "it-test-pw";

  private static Connection adminConn;
  private static Connection runtimeConn;
  private static AuditActivitiesImpl audit;
  private static ObjectMapper om;

  @BeforeAll
  static void initDb() throws Exception {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();

    adminConn =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    adminConn.setAutoCommit(false);
    try (Statement st = adminConn.createStatement()) {
      st.execute("ALTER ROLE orchestrator_runtime PASSWORD '" + RUNTIME_PASSWORD + "'");
    }

    runtimeConn =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), "orchestrator_runtime", RUNTIME_PASSWORD);
    runtimeConn.setAutoCommit(false);

    om = new ObjectMapper().registerModule(new JavaTimeModule());
    DSLContext dsl = DSL.using(runtimeConn, SQLDialect.POSTGRES);
    AuditLogChainWriter writer = new AuditLogChainWriter(om);
    audit = new AuditActivitiesImpl(dsl, om, writer, true);
  }

  @AfterAll
  static void closeDb() throws Exception {
    if (runtimeConn != null) runtimeConn.close();
    if (adminConn != null) adminConn.close();
  }

  @Test
  void brokerTargetFlip_persistsExactlyOneEventWithChainHashes(@TempDir Path root)
      throws Exception {
    // Clean any prior IT residue under (dev, copytrade-v1) to keep the chain head deterministic.
    try (Statement st = adminConn.createStatement()) {
      st.executeUpdate(
          "DELETE FROM audit_log WHERE tenant_id = 'dev' AND strategy_id = 'copytrade-v1'");
    }
    adminConn.commit();

    Path tenantsDir = root.resolve("tenants");
    writeStrategyYaml(tenantsDir, "dev", "copytrade-v1");
    Path snapshotDir = root.resolve("snapshot");
    TenantConfigSnapshot snapshots = new TenantConfigSnapshot(om, snapshotDir);

    // Seed prior snapshot: broker_target = alpaca-paper.
    StrategyConfig prior =
        strategyConfig("dev", "copytrade-v1", StrategyConfig.BrokerTarget.ALPACA_PAPER);
    snapshots.store("dev", "copytrade-v1", TenantConfigSnapshot.canonicalize(om, prior));

    // Current StrategyConfig: broker_target = tradier-paper.
    StrategyConfig current =
        strategyConfig("dev", "copytrade-v1", StrategyConfig.BrokerTarget.TRADIER_PAPER);
    StrategyRegistry registry =
        new StrategyRegistry() {
          @Override
          public StrategyConfig get(String tenantId, String strategyId) {
            return current;
          }
        };

    TenantConfigChangedEmitter emitter =
        new TenantConfigChangedEmitter(audit, registry, om, tenantsDir, snapshots, Set.of());
    emitter.runOnce();
    runtimeConn.commit();

    try (PreparedStatement ps =
        adminConn.prepareStatement(
            "SELECT subject::text AS subject_json, prev_hash, row_hash "
                + "FROM audit_log "
                + "WHERE kind = ? AND tenant_id = ? AND strategy_id = ?")) {
      ps.setString(1, "TenantConfigChanged");
      ps.setString(2, "dev");
      ps.setString(3, "copytrade-v1");
      try (var rs = ps.executeQuery()) {
        int count = 0;
        while (rs.next()) {
          count++;
          String subjectJson = rs.getString("subject_json");
          JsonNode subject = om.readTree(subjectJson);
          assertThat(subject.get("changed_keys").isArray()).isTrue();
          assertThat(subject.get("changed_keys").get(0).textValue()).isEqualTo("broker_target");
          assertThat(subject.get("old_values").get("broker_target").textValue())
              .isEqualTo("alpaca-paper");
          assertThat(subject.get("new_values").get("broker_target").textValue())
              .isEqualTo("tradier-paper");
          assertThat(subject.get("source").textValue()).isEqualTo("configmap-reload");
          assertThat(subject.has("loaded_at")).isTrue();

          // Chain-writer must populate row_hash. prev_hash MAY be NULL at the chain head; row_hash
          // MUST be non-NULL and exactly 32 bytes (SHA-256). This pins halt-condition #1: emit
          // path goes through AuditActivities.log (which runs the chain writer), not a direct
          // INSERT that would leave NULL hashes.
          byte[] rowHash = rs.getBytes("row_hash");
          assertThat(rowHash).as("row_hash must be populated by chain writer").isNotNull();
          assertThat(rowHash.length).as("row_hash must be 32 bytes (SHA-256)").isEqualTo(32);
        }
        assertThat(count)
            .as("exactly one TenantConfigChanged row must be persisted for (dev, copytrade-v1)")
            .isEqualTo(1);
      }
    }
  }

  private static StrategyConfig strategyConfig(
      String tenantId, String strategyId, StrategyConfig.BrokerTarget brokerTarget) {
    StrategyConfig cfg = new StrategyConfig();
    cfg.setSchemaVersion(1L);
    cfg.setTenantId(tenantId);
    cfg.setStrategyId(strategyId);
    cfg.setBrokerTarget(brokerTarget);
    cfg.setAuthorWhitelist(Set.of("author-1"));
    cfg.setMaxSignalAgeBtoSecs(30L);
    cfg.setMaxSignalAgeStcSecs(60L);
    cfg.setMaxPositions(5L);
    cfg.setCapitalWeight(new BigDecimal("0.1"));
    cfg.setMinContracts(1L);
    cfg.setMaxContracts(5L);
    return cfg;
  }

  private static void writeStrategyYaml(Path tenantsDir, String tenantId, String strategyId)
      throws IOException {
    Path strategies = tenantsDir.resolve(tenantId).resolve("strategies");
    Files.createDirectories(strategies);
    Files.writeString(
        strategies.resolve(strategyId + ".yaml"), "schema_version: 1\n", StandardCharsets.UTF_8);
  }
}
