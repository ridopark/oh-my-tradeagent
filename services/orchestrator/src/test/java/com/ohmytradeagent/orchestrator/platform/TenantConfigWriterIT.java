package com.ohmytradeagent.orchestrator.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ohmytradeagent.orchestrator.activities.AuditActivitiesImpl;
import com.ohmytradeagent.orchestrator.activities.AuditLogChainWriter;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
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
 * account-loss-cap-db (Phase 3) Testcontainers IT for {@link TenantConfigWriter} (the tenant
 * tighten-only account-cap write path) over the V9 UPDATE grant. Mirrors {@code
 * StrategyConfigWriterIT}: postgres:16, Flyway {@code classpath:db/migration} (now including V9),
 * {@code RUN_DB_ITS=true} gate, delete-per-test.
 *
 * <p>Runs the writer + audit as the constrained {@code orchestrator_runtime} role, so the V9 grant
 * binds end-to-end. These are the risk-manager sign-off behavioral assertions: raise/remove/add
 * REJECTED, a valid tighten UPDATED with one honored audit, a below-floor tighten REJECTED (C2),
 * and every rejection leaves a durable rejection audit tripwire (C4). C5: a cap edit only mutates
 * {@code tenant_config} + emits an {@code AccountLossCapChanged} — it never touches any
 * kill-switch/reset path (the writer has no such collaborator).
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class TenantConfigWriterIT {

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  private static final String RUNTIME_PASSWORD = "it-test-pw";
  private static final String TENANT = "dev";

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

    // The writer + audit run as the constrained runtime role so the V9 grant binds end-to-end.
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
      st.execute("DELETE FROM tenant_config");
      st.execute("DELETE FROM audit_log");
    }
  }

  /** V9 grants UPDATE on tenant_config to the constrained runtime role. */
  @Test
  void v9GrantsUpdate() throws Exception {
    seedRow(TENANT, new BigDecimal("2000"), new BigDecimal("0.40"), 1L);
    try (Statement st = runtimeConn.createStatement()) {
      int updated =
          st.executeUpdate(
              "UPDATE tenant_config SET updated_by = 'role-it' WHERE tenant_id = '" + TENANT + "'");
      assertThat(updated).as("V9 must grant UPDATE to orchestrator_runtime").isEqualTo(1);
    }
  }

  /**
   * stored pct=0.40, PUT pct=0.30 → UPDATED, version bumped, one honored audit prior 0.40/current
   * 0.30.
   */
  @Test
  void tighten_pctLowered_updatesAndBumpsVersion_withOneChangedAudit() throws Exception {
    seedRow(TENANT, new BigDecimal("2000"), new BigDecimal("0.40"), 1L);

    long newVersion =
        writer().update(TENANT, new BigDecimal("2000"), new BigDecimal("0.30"), 1L, "alice");

    assertThat(newVersion).isEqualTo(2L);
    assertThat(versionOf(TENANT)).isEqualTo(2L);
    assertThat(pctOf(TENANT)).isEqualByComparingTo("0.30");
    assertThat(updatedBy(TENANT)).isEqualTo("alice");

    JsonNode subject = singleAuditSubject("changed");
    assertThat(subject.get("actor").textValue()).isEqualTo("alice");
    assertThat(subject.get("source").textValue()).isEqualTo("tenant-cap-write");
    assertThat(subject.get("prior").get("pct").decimalValue()).isEqualByComparingTo("0.40");
    assertThat(subject.get("current").get("pct").decimalValue()).isEqualByComparingTo("0.30");
    assertThat(subject.get("old_version").asLong()).isEqualTo(1L);
    assertThat(subject.get("new_version").asLong()).isEqualTo(2L);
    assertThat(rowHashOf("changed")).as("honored audit is hash-chained").isNotNull();
  }

  /**
   * stored pct=0.40, PUT pct=0.60 (raise) → REJECTED_TIGHTEN_ONLY, row unchanged, rejection audit
   * (C4).
   */
  @Test
  void raise_isRejectedTightenOnly_rowUnchanged_withRejectionAudit() throws Exception {
    seedRow(TENANT, new BigDecimal("2000"), new BigDecimal("0.40"), 1L);

    assertThatThrownBy(
            () ->
                writer()
                    .update(TENANT, new BigDecimal("2000"), new BigDecimal("0.60"), 1L, "mallory"))
        .isInstanceOf(DangerousFieldChangeRejected.class);

    assertThat(versionOf(TENANT)).isEqualTo(1L);
    assertThat(pctOf(TENANT)).isEqualByComparingTo("0.40");
    assertThat(auditCount("changed")).isZero();

    JsonNode subject = singleAuditSubject("rejected_tighten_only");
    assertThat(subject.get("actor").textValue()).isEqualTo("mallory");
    assertThat(subject.get("stored").get("pct").decimalValue()).isEqualByComparingTo("0.40");
    assertThat(subject.get("attempted").get("pct").decimalValue()).isEqualByComparingTo("0.60");
  }

  /**
   * stored pct=0.40, PUT pct=null (remove) → REJECTED_TIGHTEN_ONLY (dropping a cap is not a
   * tighten).
   */
  @Test
  void remove_isRejectedTightenOnly() throws Exception {
    seedRow(TENANT, new BigDecimal("2000"), new BigDecimal("0.40"), 1L);

    assertThatThrownBy(() -> writer().update(TENANT, new BigDecimal("2000"), null, 1L, "mallory"))
        .isInstanceOf(DangerousFieldChangeRejected.class);

    assertThat(versionOf(TENANT)).isEqualTo(1L);
    assertThat(pctOf(TENANT)).isEqualByComparingTo("0.40");
    assertThat(auditCount("rejected_tighten_only")).isEqualTo(1);
  }

  /** stored threshold=null, PUT threshold=2500 → REJECTED_TIGHTEN_ONLY (adding an absent cap). */
  @Test
  void addWhereNull_isRejectedTightenOnly() throws Exception {
    seedRow(TENANT, null, new BigDecimal("0.40"), 1L);

    assertThatThrownBy(
            () ->
                writer()
                    .update(TENANT, new BigDecimal("2500"), new BigDecimal("0.40"), 1L, "mallory"))
        .isInstanceOf(DangerousFieldChangeRejected.class);

    assertThat(versionOf(TENANT)).isEqualTo(1L);
    assertThat(thresholdOf(TENANT)).isNull();
    assertThat(auditCount("rejected_tighten_only")).isEqualTo(1);
  }

  /** stale expected_version → REJECTED_STALE_VERSION, no write, no audit. */
  @Test
  void staleExpectedVersion_isRejectedStale() throws Exception {
    seedRow(TENANT, new BigDecimal("2000"), new BigDecimal("0.40"), 1L);
    writer().update(TENANT, new BigDecimal("2000"), new BigDecimal("0.30"), 1L, "alice"); // → v2

    assertThatThrownBy(
            () ->
                writer().update(TENANT, new BigDecimal("2000"), new BigDecimal("0.20"), 1L, "bob"))
        .isInstanceOf(OptimisticLockException.class);

    assertThat(versionOf(TENANT)).isEqualTo(2L);
    assertThat(pctOf(TENANT)).isEqualByComparingTo("0.30");
    // Exactly one honored audit (from the v2 write); the stale attempt writes no audit.
    assertThat(auditCount("changed")).isEqualTo(1);
  }

  /** pct=40 (typo, > 1) → REJECTED_INVALID; no audit (a client typo, not an abuse tripwire). */
  @Test
  void pctOutOfRange_isRejectedInvalid_noAudit() throws Exception {
    seedRow(TENANT, new BigDecimal("2000"), new BigDecimal("0.40"), 1L);

    assertThatThrownBy(
            () ->
                writer().update(TENANT, new BigDecimal("2000"), new BigDecimal("40"), 1L, "alice"))
        .isInstanceOf(InvalidConfigException.class);

    assertThat(versionOf(TENANT)).isEqualTo(1L);
    assertThat(totalAuditCount()).isZero();
  }

  /** pct=0 → REJECTED_INVALID (a 0 cap is forbidden). */
  @Test
  void pctZero_isRejectedInvalid() throws Exception {
    seedRow(TENANT, new BigDecimal("2000"), new BigDecimal("0.40"), 1L);

    assertThatThrownBy(
            () -> writer().update(TENANT, new BigDecimal("2000"), BigDecimal.ZERO, 1L, "alice"))
        .isInstanceOf(InvalidConfigException.class);

    assertThat(versionOf(TENANT)).isEqualTo(1L);
  }

  /** stored pct=0.40, PUT pct=0.001 (valid tighten, below floor) → REJECTED_BELOW_FLOOR (C2). */
  @Test
  void tightenBelowFloor_isRejectedBelowFloor_withRejectionAudit() throws Exception {
    seedRow(TENANT, new BigDecimal("2000"), new BigDecimal("0.40"), 1L);

    assertThatThrownBy(
            () ->
                writer()
                    .update(TENANT, new BigDecimal("2000"), new BigDecimal("0.001"), 1L, "mallory"))
        .isInstanceOf(BelowFloorRejected.class);

    assertThat(versionOf(TENANT)).isEqualTo(1L);
    assertThat(pctOf(TENANT)).isEqualByComparingTo("0.40");
    assertThat(auditCount("rejected_below_floor")).isEqualTo(1);
  }

  /**
   * C5: a cap edit only mutates tenant_config and emits exactly ONE AccountLossCapChanged audit —
   * it NEVER touches a kill-switch/reset path (the writer has no such collaborator, so a tripped
   * switch cannot be un-tripped by a cap edit). Proven here by: the ONLY audit kind written by a
   * successful update is AccountLossCapChanged (no KillSwitch* / *Reset* kind appears).
   */
  @Test
  void capEdit_neverTouchesKillSwitchState_onlyAccountLossCapChangedAudit() throws Exception {
    seedRow(TENANT, new BigDecimal("2000"), new BigDecimal("0.40"), 1L);

    writer().update(TENANT, new BigDecimal("2000"), new BigDecimal("0.30"), 1L, "alice");

    try (Statement st = adminConn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT DISTINCT kind FROM audit_log WHERE tenant_id = '" + TENANT + "'")) {
      int kinds = 0;
      while (rs.next()) {
        kinds++;
        assertThat(rs.getString("kind")).isEqualTo("AccountLossCapChanged");
      }
      assertThat(kinds).as("a cap edit writes only the AccountLossCapChanged kind").isEqualTo(1);
    }
  }

  // --- helpers ---

  private static TenantConfigWriter writer() {
    return new TenantConfigWriter(dsl, audit);
  }

  private static void seedRow(String tenantId, BigDecimal threshold, BigDecimal pct, long version)
      throws Exception {
    try (PreparedStatement ps =
        adminConn.prepareStatement(
            "INSERT INTO tenant_config "
                + "(tenant_id, account_daily_loss_threshold, account_daily_loss_pct, version, updated_by) "
                + "VALUES (?, ?, ?, ?, 'seed:it')")) {
      ps.setString(1, tenantId);
      if (threshold == null) {
        ps.setNull(2, java.sql.Types.NUMERIC);
      } else {
        ps.setBigDecimal(2, threshold);
      }
      if (pct == null) {
        ps.setNull(3, java.sql.Types.NUMERIC);
      } else {
        ps.setBigDecimal(3, pct);
      }
      ps.setLong(4, version);
      ps.executeUpdate();
    }
  }

  private static long versionOf(String tenantId) throws Exception {
    return scalarLong("SELECT version FROM tenant_config WHERE tenant_id = ?", tenantId);
  }

  private static BigDecimal pctOf(String tenantId) throws Exception {
    return scalarDecimal(
        "SELECT account_daily_loss_pct FROM tenant_config WHERE tenant_id = ?", tenantId);
  }

  private static BigDecimal thresholdOf(String tenantId) throws Exception {
    return scalarDecimal(
        "SELECT account_daily_loss_threshold FROM tenant_config WHERE tenant_id = ?", tenantId);
  }

  private static String updatedBy(String tenantId) throws Exception {
    try (PreparedStatement ps =
        adminConn.prepareStatement("SELECT updated_by FROM tenant_config WHERE tenant_id = ?")) {
      ps.setString(1, tenantId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  private static long scalarLong(String sql, String tenantId) throws Exception {
    try (PreparedStatement ps = adminConn.prepareStatement(sql)) {
      ps.setString(1, tenantId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private static BigDecimal scalarDecimal(String sql, String tenantId) throws Exception {
    try (PreparedStatement ps = adminConn.prepareStatement(sql)) {
      ps.setString(1, tenantId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getBigDecimal(1);
      }
    }
  }

  /**
   * Fetches the single AccountLossCapChanged audit subject with the given outcome; asserts exactly
   * one.
   */
  private static JsonNode singleAuditSubject(String outcome) throws Exception {
    try (PreparedStatement ps =
        adminConn.prepareStatement(
            "SELECT subject::text AS s FROM audit_log "
                + "WHERE kind = 'AccountLossCapChanged' AND subject->>'outcome' = ?")) {
      ps.setString(1, outcome);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("expected one %s audit row", outcome).isTrue();
        JsonNode subject = om.readTree(rs.getString("s"));
        assertThat(rs.next()).as("expected exactly one %s audit row", outcome).isFalse();
        return subject;
      }
    }
  }

  private static int auditCount(String outcome) throws Exception {
    try (PreparedStatement ps =
        adminConn.prepareStatement(
            "SELECT count(*) FROM audit_log "
                + "WHERE kind = 'AccountLossCapChanged' AND subject->>'outcome' = ?")) {
      ps.setString(1, outcome);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private static int totalAuditCount() throws Exception {
    try (Statement st = adminConn.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM audit_log")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private static byte[] rowHashOf(String outcome) throws Exception {
    try (PreparedStatement ps =
        adminConn.prepareStatement(
            "SELECT row_hash FROM audit_log "
                + "WHERE kind = 'AccountLossCapChanged' AND subject->>'outcome' = ?")) {
      ps.setString(1, outcome);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getBytes(1);
      }
    }
  }
}
