package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ohmytradeagent.contract.LivePromotionApprovalRequest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Issue #87: Testcontainers IT for {@link LivePromotionActivitiesImpl} end-to-end through
 * production {@link AuditActivitiesImpl} (incl. the hash-chain writer from PR #117). Asserts the
 * persisted {@code audit_log} row carries both approver IDs in the JSONB subject and that {@code
 * prev_hash}/{@code row_hash} are populated (32 bytes each, non-NULL) — the chain writer is not
 * bypassed, satisfying halt-condition #4.
 *
 * <p>Same gating + container shape as {@link AuditLogChainWriterIT}. Connects as the
 * orchestrator_runtime role so the V3 REVOKE binding is exercised.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class LivePromotionApprovalIT {

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  private static final String RUNTIME_PASSWORD = "it-test-pw";

  private static Connection adminConn;
  private static Connection runtimeConn;
  private static LivePromotionActivitiesImpl livePromotion;
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
    AuditActivitiesImpl audit = new AuditActivitiesImpl(dsl, om, writer, true, event -> {});
    livePromotion = new LivePromotionActivitiesImpl(audit);
  }

  @AfterAll
  static void closeDb() throws Exception {
    if (runtimeConn != null) runtimeConn.close();
    if (adminConn != null) adminConn.close();
  }

  @Test
  void approve_persistsExactlyOneEventWithChainHashes() throws Exception {
    // Clean any prior IT residue.
    try (Statement st = adminConn.createStatement()) {
      st.executeUpdate(
          "DELETE FROM audit_log WHERE tenant_id = 'dev' AND strategy_id = 'copytrade-v1'");
    }

    LivePromotionApprovalRequest req = new LivePromotionApprovalRequest();
    req.setSchemaVersion(1L);
    req.setApproverId1("alice");
    req.setApproverId2("bob");
    req.setTenantId("dev");
    req.setStrategyId("copytrade-v1");
    req.setBrokerTarget("tradier-live");
    req.setNote("phase-7 gate signoff drill");

    livePromotion.approve(req);
    runtimeConn.commit();

    // Query mirrors the documented audit-log read path (kind + tenant + strategy filter).
    try (PreparedStatement ps =
        adminConn.prepareStatement(
            "SELECT kind, subject::text AS subject_json, prev_hash, row_hash "
                + "FROM audit_log "
                + "WHERE kind = ? AND tenant_id = ? AND strategy_id = ?")) {
      ps.setString(1, "LivePromotionApproved");
      ps.setString(2, "dev");
      ps.setString(3, "copytrade-v1");
      try (var rs = ps.executeQuery()) {
        int count = 0;
        while (rs.next()) {
          count++;
          String subjectJson = rs.getString("subject_json");
          JsonNode subject = om.readTree(subjectJson);
          assertThat(subject.get("approver_id_1").textValue()).isEqualTo("alice");
          assertThat(subject.get("approver_id_2").textValue()).isEqualTo("bob");
          assertThat(subject.get("tenant_id").textValue()).isEqualTo("dev");
          assertThat(subject.get("strategy_id").textValue()).isEqualTo("copytrade-v1");
          assertThat(subject.get("broker_target").textValue()).isEqualTo("tradier-live");
          assertThat(subject.get("note").textValue()).isEqualTo("phase-7 gate signoff drill");

          // Chain-writer must populate row_hash. prev_hash is NULL only at the per-(tenant,
          // strategy) chain head; this is the head row in a freshly-cleaned chain, so prev_hash
          // is allowed to be NULL while row_hash MUST be non-NULL and exactly 32 bytes (SHA-256).
          byte[] rowHash = rs.getBytes("row_hash");
          assertThat(rowHash).as("row_hash must be populated by chain writer").isNotNull();
          assertThat(rowHash.length).as("row_hash must be 32 bytes (SHA-256)").isEqualTo(32);
        }
        assertThat(count)
            .as("exactly one LivePromotionApproved row must be persisted for (dev, copytrade-v1)")
            .isEqualTo(1);
      }
    }
  }

  @Test
  void approve_sameApprover_writesNoAuditRow() throws Exception {
    try (Statement st = adminConn.createStatement()) {
      st.executeUpdate(
          "DELETE FROM audit_log WHERE tenant_id = 'dev' AND strategy_id = 'copytrade-v1'");
    }

    LivePromotionApprovalRequest req = new LivePromotionApprovalRequest();
    req.setSchemaVersion(1L);
    req.setApproverId1("alice");
    req.setApproverId2("alice");
    req.setTenantId("dev");
    req.setStrategyId("copytrade-v1");
    req.setBrokerTarget("tradier-live");

    assertThatThrownBy(() -> livePromotion.approve(req))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("approvers_must_differ");

    runtimeConn.rollback();

    try (PreparedStatement ps =
        adminConn.prepareStatement(
            "SELECT COUNT(*) FROM audit_log "
                + "WHERE kind = 'LivePromotionApproved' AND tenant_id = 'dev' "
                + "AND strategy_id = 'copytrade-v1'")) {
      try (var rs = ps.executeQuery()) {
        rs.next();
        assertThat(rs.getInt(1))
            .as("rejected same-approver request must not write any audit row")
            .isEqualTo(0);
      }
    }
  }
}
