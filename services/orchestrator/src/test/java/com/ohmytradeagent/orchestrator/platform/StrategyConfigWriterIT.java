package com.ohmytradeagent.orchestrator.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.activities.AuditActivitiesImpl;
import com.ohmytradeagent.orchestrator.activities.AuditLogChainWriter;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.util.PSQLException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * P0c-a Testcontainers IT for {@link StrategyConfigWriter} (the runtime config write path) over the
 * V6 UPDATE grant. Mirrors {@link StrategyConfigStoreIT} exactly: postgres:16, Flyway {@code
 * classpath:db/migration} (now including V6), {@code RUN_DB_ITS=true} gate, truncate per-test.
 *
 * <p>Exercises the production {@code orchestrator_runtime} role for the V6 grant assertion (UPDATE
 * permitted, DELETE still denied) and drives the writer through the production {@link
 * AuditActivitiesImpl} with the chain writer enabled, so the {@code row_hash} assertion proves the
 * audit row is written via the hash-chain path and not a direct INSERT.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class StrategyConfigWriterIT {

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  private static final String RUNTIME_PASSWORD = "it-test-pw";

  private static Connection adminConn;
  private static Connection runtimeConn;
  private static DSLContext dsl;
  private static ObjectMapper om;
  private static AuditActivitiesImpl audit;

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
    try (Statement st = adminConn.createStatement()) {
      st.execute("ALTER ROLE orchestrator_runtime PASSWORD '" + RUNTIME_PASSWORD + "'");
    }

    // The writer + audit run as the constrained runtime role so the V6 grant binds end-to-end.
    runtimeConn =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), "orchestrator_runtime", RUNTIME_PASSWORD);

    om = new ObjectMapper().registerModule(new JavaTimeModule());
    dsl = DSL.using(runtimeConn, SQLDialect.POSTGRES);
    AuditLogChainWriter chainWriter = new AuditLogChainWriter(om);
    audit = new AuditActivitiesImpl(dsl, om, chainWriter, true, event -> {});
  }

  @AfterAll
  static void closeDb() throws Exception {
    if (runtimeConn != null) runtimeConn.close();
    if (adminConn != null) adminConn.close();
  }

  @BeforeEach
  void truncate() throws Exception {
    try (Statement st = adminConn.createStatement()) {
      st.execute("DELETE FROM strategy_config");
      st.execute("DELETE FROM audit_log");
    }
  }

  /** V6 grants UPDATE on strategy_config to orchestrator_runtime; DELETE remains denied. */
  @Test
  void v6GrantsUpdate() throws Exception {
    seedRow("dev", "copytrade-v1", liveSafeConfig("dev", "copytrade-v1"), 1L);

    try (Statement st = runtimeConn.createStatement()) {
      int updated =
          st.executeUpdate(
              "UPDATE strategy_config SET updated_by = 'role-it' "
                  + "WHERE tenant_id = 'dev' AND strategy_id = 'copytrade-v1'");
      assertThat(updated).as("V6 must grant UPDATE to orchestrator_runtime").isEqualTo(1);
    }

    assertThatThrownBy(
            () -> {
              try (Statement st = runtimeConn.createStatement()) {
                st.executeUpdate(
                    "DELETE FROM strategy_config "
                        + "WHERE tenant_id = 'dev' AND strategy_id = 'copytrade-v1'");
              }
            })
        .isInstanceOf(PSQLException.class)
        .satisfies(e -> assertThat(((PSQLException) e).getSQLState()).isEqualTo("42501"));
  }

  /** A SAFE-only (here, tighten-allowed) change persists and bumps version 1 → 2. */
  @Test
  void safeFieldPersistsAndBumpsVersion() throws Exception {
    StrategyConfig stored = liveSafeConfig("dev", "copytrade-v1");
    stored.setMaxPositions(5L);
    seedRow("dev", "copytrade-v1", stored, 1L);

    StrategyConfig next = copy(stored);
    next.setMaxPositions(3L); // tighten (equal-or-lower exposure allowed)

    StrategyConfigWriter writer = new StrategyConfigWriter(dsl, om, audit);
    long newVersion = writer.update("dev", "copytrade-v1", next, 1L, "alice");

    assertThat(newVersion).isEqualTo(2L);
    assertThat(versionOf("dev", "copytrade-v1")).isEqualTo(2L);
    JsonNode blob = configJson("dev", "copytrade-v1");
    assertThat(blob.get("max_positions").asLong()).isEqualTo(3L);
    assertThat(updatedBy("dev", "copytrade-v1")).isEqualTo("alice");
  }

  /** A stale expectedVersion (row already at 2) throws OptimisticLockException; row stays at 2. */
  @Test
  void staleExpectedVersionThrowsOptimisticLock() throws Exception {
    StrategyConfig stored = liveSafeConfig("dev", "copytrade-v1");
    seedRow("dev", "copytrade-v1", stored, 1L);

    StrategyConfigWriter writer = new StrategyConfigWriter(dsl, om, audit);
    StrategyConfig next = copy(stored);
    next.setMaxPositions(4L);
    writer.update("dev", "copytrade-v1", next, 1L, "alice"); // → version 2

    StrategyConfig stale = copy(stored);
    stale.setMaxPositions(3L);
    assertThatThrownBy(() -> writer.update("dev", "copytrade-v1", stale, 1L, "bob"))
        .isInstanceOf(OptimisticLockException.class);

    assertThat(versionOf("dev", "copytrade-v1")).isEqualTo(2L);
  }

  /** An absent (tenant, strategy) throws StrategyNotFoundException. */
  @Test
  void rowAbsentThrowsStrategyNotFound() {
    StrategyConfigWriter writer = new StrategyConfigWriter(dsl, om, audit);
    StrategyConfig next = liveSafeConfig("dev", "ghost");
    assertThatThrownBy(() -> writer.update("dev", "ghost", next, 1L, "alice"))
        .isInstanceOf(YamlStrategyRegistry.StrategyNotFoundException.class);
  }

  /** A DANGEROUS broker_target change persists nothing (blob + version unchanged). */
  @Test
  void rejectsBrokerTargetChange_persistsNothing() throws Exception {
    StrategyConfig stored = liveSafeConfig("dev", "copytrade-v1");
    stored.setBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_PAPER);
    seedRow("dev", "copytrade-v1", stored, 1L);
    String before = configJson("dev", "copytrade-v1").toString();

    StrategyConfig next = copy(stored);
    next.setBrokerTarget(StrategyConfig.BrokerTarget.TRADIER_PAPER);

    StrategyConfigWriter writer = new StrategyConfigWriter(dsl, om, audit);
    assertThatThrownBy(() -> writer.update("dev", "copytrade-v1", next, 1L, "alice"))
        .isInstanceOf(DangerousFieldChangeRejected.class);

    assertThat(versionOf("dev", "copytrade-v1")).isEqualTo(1L);
    assertThat(configJson("dev", "copytrade-v1").toString()).isEqualTo(before);
  }

  /** The disarm vector at the DB layer: a daily_loss_threshold change persists nothing. */
  @Test
  void rejectsDailyLossChange_persistsNothing() throws Exception {
    StrategyConfig stored = liveSafeConfig("dev", "copytrade-v1"); // daily_loss_threshold = 500
    seedRow("dev", "copytrade-v1", stored, 1L);
    String before = configJson("dev", "copytrade-v1").toString();

    StrategyConfig next = copy(stored);
    next.setDailyLossThreshold(new BigDecimal("5000")); // disarm attempt

    StrategyConfigWriter writer = new StrategyConfigWriter(dsl, om, audit);
    assertThatThrownBy(() -> writer.update("dev", "copytrade-v1", next, 1L, "alice"))
        .isInstanceOf(DangerousFieldChangeRejected.class);

    assertThat(versionOf("dev", "copytrade-v1")).isEqualTo(1L);
    assertThat(configJson("dev", "copytrade-v1").toString()).isEqualTo(before);
  }

  /**
   * A SCALE-ONLY difference in a DANGEROUS BigDecimal field (daily_loss_threshold 500 → 500.00) is
   * NOT a value change → ACCEPTED. BigDecimal.equals is scale-sensitive, but a JSON round-trip (the
   * UI-P4 edit path re-posts the full config) drops/adds trailing zeros, so a scale-sensitive
   * compare would falsely 403 every save. compareTo == 0 is the correct "unchanged value" test.
   */
  @Test
  void acceptsDailyLossThresholdScaleOnlyChange_theJsonRoundTripCase() throws Exception {
    StrategyConfig stored = liveSafeConfig("dev", "copytrade-v1"); // daily_loss_threshold = 500
    seedRow("dev", "copytrade-v1", stored, 1L);

    StrategyConfig next = copy(stored);
    next.setDailyLossThreshold(new BigDecimal("500.00")); // same value, scale 2 (vs stored scale 0)
    next.setMaxPositions(3L); // a real tighten alongside, like a UI edit that re-posts everything

    StrategyConfigWriter writer = new StrategyConfigWriter(dsl, om, audit);
    long newVersion = writer.update("dev", "copytrade-v1", next, 1L, "alice");

    assertThat(newVersion).isEqualTo(2L); // accepted, NOT REJECTED_DANGEROUS on the scale diff
    assertThat(configJson("dev", "copytrade-v1").get("max_positions").asLong()).isEqualTo(3L);
  }

  /** An EXPOSURE increase (max_contracts up) persists nothing. */
  @Test
  void rejectsExposureIncrease_persistsNothing() throws Exception {
    StrategyConfig stored = liveSafeConfig("dev", "copytrade-v1"); // max_contracts = 5
    seedRow("dev", "copytrade-v1", stored, 1L);
    String before = configJson("dev", "copytrade-v1").toString();

    StrategyConfig next = copy(stored);
    next.setMaxContracts(10L);

    StrategyConfigWriter writer = new StrategyConfigWriter(dsl, om, audit);
    assertThatThrownBy(() -> writer.update("dev", "copytrade-v1", next, 1L, "alice"))
        .isInstanceOf(DangerousFieldChangeRejected.class);

    assertThat(versionOf("dev", "copytrade-v1")).isEqualTo(1L);
    assertThat(configJson("dev", "copytrade-v1").toString()).isEqualTo(before);
  }

  /**
   * A successful safe write emits exactly one TenantConfigChanged audit row with a non-NULL hash.
   */
  @Test
  void emitsTenantConfigChangedAuditRowOnSuccess() throws Exception {
    StrategyConfig stored = liveSafeConfig("dev", "copytrade-v1");
    seedRow("dev", "copytrade-v1", stored, 1L);

    StrategyConfig next = copy(stored);
    next.setMaxPositions(3L);

    StrategyConfigWriter writer = new StrategyConfigWriter(dsl, om, audit);
    writer.update("dev", "copytrade-v1", next, 1L, "alice");

    try (var ps =
        adminConn.prepareStatement(
            "SELECT subject::text AS subject_json, row_hash FROM audit_log "
                + "WHERE kind = 'TenantConfigChanged' AND tenant_id = 'dev' "
                + "AND strategy_id = 'copytrade-v1'")) {
      try (var rs = ps.executeQuery()) {
        int count = 0;
        while (rs.next()) {
          count++;
          JsonNode subject = om.readTree(rs.getString("subject_json"));
          assertThat(subject.get("actor").textValue()).isEqualTo("alice");
          assertThat(subject.get("source").textValue()).isEqualTo("runtime-write");
          byte[] rowHash = rs.getBytes("row_hash");
          assertThat(rowHash).as("row_hash must be populated by chain writer").isNotNull();
        }
        assertThat(count).isEqualTo(1);
      }
    }
  }

  /** A rejected write emits no TenantConfigChanged audit row. */
  @Test
  void noAuditRowOnRejection() throws Exception {
    StrategyConfig stored = liveSafeConfig("dev", "copytrade-v1");
    seedRow("dev", "copytrade-v1", stored, 1L);

    StrategyConfig next = copy(stored);
    next.setDailyLossThreshold(new BigDecimal("5000"));

    StrategyConfigWriter writer = new StrategyConfigWriter(dsl, om, audit);
    assertThatThrownBy(() -> writer.update("dev", "copytrade-v1", next, 1L, "alice"))
        .isInstanceOf(DangerousFieldChangeRejected.class);

    try (Statement st = adminConn.createStatement();
        var rs =
            st.executeQuery(
                "SELECT count(*) FROM audit_log WHERE kind = 'TenantConfigChanged' "
                    + "AND tenant_id = 'dev' AND strategy_id = 'copytrade-v1'")) {
      rs.next();
      assertThat(rs.getInt(1)).isZero();
    }
  }

  // --- Phase I-1b: create-tenant INSERT path ---

  /** A create INSERTs the first row at version 1 with updated_by = the operator. */
  @Test
  void create_insertsFirstRowAtVersionOne() throws Exception {
    StrategyConfig config = liveSafeConfig("acme", "copytrade-v1");

    StrategyConfigWriter writer = new StrategyConfigWriter(dsl, om, audit);
    long version = writer.create("acme", "copytrade-v1", config, "ridopark@gmail.com");

    assertThat(version).isEqualTo(1L);
    assertThat(versionOf("acme", "copytrade-v1")).isEqualTo(1L);
    assertThat(updatedBy("acme", "copytrade-v1")).isEqualTo("ridopark@gmail.com");
    assertThat(configJson("acme", "copytrade-v1").get("broker_target").textValue())
        .isEqualTo("alpaca-live");
  }

  /** A second create for the same (tenant, strategy) is a no-op → RowAlreadyExistsException. */
  @Test
  void create_duplicate_throwsRowAlreadyExists_doesNotOverwrite() throws Exception {
    StrategyConfig first = liveSafeConfig("acme", "copytrade-v1");
    StrategyConfigWriter writer = new StrategyConfigWriter(dsl, om, audit);
    writer.create("acme", "copytrade-v1", first, "ridopark@gmail.com");

    StrategyConfig second = copy(first);
    second.setMaxPositions(99L); // would overwrite if create were an upsert
    assertThatThrownBy(() -> writer.create("acme", "copytrade-v1", second, "someone-else"))
        .isInstanceOf(RowAlreadyExistsException.class);

    // unchanged: still the first row's value + version + operator
    assertThat(versionOf("acme", "copytrade-v1")).isEqualTo(1L);
    assertThat(configJson("acme", "copytrade-v1").get("max_positions").asLong()).isEqualTo(5L);
    assertThat(updatedBy("acme", "copytrade-v1")).isEqualTo("ridopark@gmail.com");
  }

  /** A config whose own tenant_id/strategy_id drift from the create target is REJECTED_INVALID. */
  @Test
  void create_identityMismatch_throwsInvalidConfig_persistsNothing() {
    StrategyConfig config = liveSafeConfig("other", "copytrade-v1"); // config says tenant "other"

    StrategyConfigWriter writer = new StrategyConfigWriter(dsl, om, audit);
    assertThatThrownBy(() -> writer.create("acme", "copytrade-v1", config, "ridopark@gmail.com"))
        .isInstanceOf(InvalidConfigException.class);

    assertThat(dsl.fetchCount(org.jooq.impl.DSL.table("strategy_config"))).isZero();
  }

  /**
   * A LIVE create missing the loss gate is rejected (the live-required gate), nothing persisted.
   */
  @Test
  void create_liveMissingLossGate_throwsInvalidConfig() {
    StrategyConfig config = liveSafeConfig("acme", "copytrade-v1");
    config.setDailyLossThreshold(null); // a live strategy must declare a kill-switch loss threshold

    StrategyConfigWriter writer = new StrategyConfigWriter(dsl, om, audit);
    assertThatThrownBy(() -> writer.create("acme", "copytrade-v1", config, "ridopark@gmail.com"))
        .isInstanceOf(InvalidConfigException.class);

    assertThat(dsl.fetchCount(org.jooq.impl.DSL.table("strategy_config"))).isZero();
  }

  /** A PAPER create needs no loss gates (they apply only to live) → CREATED at version 1. */
  @Test
  void create_paperConfig_succeeds_withoutLiveGates() throws Exception {
    StrategyConfig config = liveSafeConfig("acme", "copytrade-v1");
    config.setBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_PAPER);
    config.setDailyLossThreshold(null); // not required for paper
    config.setNotionalCapPctOfCapitalBase(null); // not required for paper

    StrategyConfigWriter writer = new StrategyConfigWriter(dsl, om, audit);
    long version = writer.create("acme", "copytrade-v1", config, "ridopark@gmail.com");

    assertThat(version).isEqualTo(1L);
    assertThat(configJson("acme", "copytrade-v1").get("broker_target").textValue())
        .isEqualTo("alpaca-paper");
  }

  /** A successful create emits exactly one TenantConfigChanged audit row, source=tenant-create. */
  @Test
  void create_emitsTenantCreateAuditRow() throws Exception {
    StrategyConfig config = liveSafeConfig("acme", "copytrade-v1");

    StrategyConfigWriter writer = new StrategyConfigWriter(dsl, om, audit);
    writer.create("acme", "copytrade-v1", config, "ridopark@gmail.com");

    try (var ps =
        adminConn.prepareStatement(
            "SELECT subject::text AS subject_json, row_hash FROM audit_log "
                + "WHERE kind = 'TenantConfigChanged' AND tenant_id = 'acme' "
                + "AND strategy_id = 'copytrade-v1'")) {
      try (var rs = ps.executeQuery()) {
        int count = 0;
        while (rs.next()) {
          count++;
          JsonNode subject = om.readTree(rs.getString("subject_json"));
          assertThat(subject.get("actor").textValue()).isEqualTo("ridopark@gmail.com");
          assertThat(subject.get("source").textValue()).isEqualTo("tenant-create");
          assertThat(rs.getBytes("row_hash")).as("row_hash via chain writer").isNotNull();
        }
        assertThat(count).isEqualTo(1);
      }
    }
  }

  // --- helpers ---

  private static StrategyConfig liveSafeConfig(String tenantId, String strategyId) {
    StrategyConfig c = new StrategyConfig();
    c.setSchemaVersion(1L);
    c.setTenantId(tenantId);
    c.setStrategyId(strategyId);
    c.setBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_LIVE);
    c.setAuthorWhitelist(new LinkedHashSet<>(List.of("author-1")));
    c.setMaxSignalAgeBtoSecs(30L);
    c.setMaxSignalAgeStcSecs(60L);
    c.setMaxPositions(5L);
    c.setCapitalWeight(new BigDecimal("0.10"));
    c.setMinContracts(1L);
    c.setMaxContracts(5L);
    c.setDailyLossThreshold(new BigDecimal("500"));
    c.setNotionalCapPctOfCapitalBase(new BigDecimal("0.25"));
    return c;
  }

  private void seedRow(String tenantId, String strategyId, StrategyConfig cfg, long version)
      throws Exception {
    String json = om.writeValueAsString(cfg);
    try (var ps =
        adminConn.prepareStatement(
            "INSERT INTO strategy_config "
                + "(tenant_id, strategy_id, schema_version, config, version, updated_by) "
                + "VALUES (?, ?, ?, ?::jsonb, ?, ?)")) {
      ps.setString(1, tenantId);
      ps.setString(2, strategyId);
      ps.setInt(3, cfg.getSchemaVersion().intValue());
      ps.setString(4, json);
      ps.setLong(5, version);
      ps.setString(6, "seed");
      ps.executeUpdate();
    }
  }

  private StrategyConfig copy(StrategyConfig src) throws Exception {
    return om.readValue(om.writeValueAsString(src), StrategyConfig.class);
  }

  private Long versionOf(String tenantId, String strategyId) {
    return dsl.fetchOne(
            "SELECT version FROM strategy_config WHERE tenant_id = ? AND strategy_id = ?",
            tenantId,
            strategyId)
        .get("version", Long.class);
  }

  private String updatedBy(String tenantId, String strategyId) {
    return dsl.fetchOne(
            "SELECT updated_by FROM strategy_config WHERE tenant_id = ? AND strategy_id = ?",
            tenantId,
            strategyId)
        .get("updated_by", String.class);
  }

  private JsonNode configJson(String tenantId, String strategyId) throws Exception {
    String text =
        dsl.fetchOne(
                "SELECT config::text AS config_text FROM strategy_config "
                    + "WHERE tenant_id = ? AND strategy_id = ?",
                tenantId,
                strategyId)
            .get("config_text", String.class);
    return om.readTree(text);
  }
}
