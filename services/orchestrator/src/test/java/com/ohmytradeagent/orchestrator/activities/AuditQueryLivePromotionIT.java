package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ohmytradeagent.contract.LivePromotionApprovalRequest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * P3-a (multi-tenant-broker-credentials): Testcontainers IT for {@link
 * AuditQueryActivitiesImpl#checkLivePromotion}, the live-promotion safety-gate verify. Mirrors
 * {@link LivePromotionApprovalIT} — same container shape, same gating, and seeds {@code
 * LivePromotionApproved} rows by calling the REAL {@link LivePromotionActivitiesImpl#approve} so
 * the JSONB subject shape (incl. {@code broker_target} and {@code occurred_at == approved_at}) is
 * authoritative rather than hand-rolled.
 *
 * <p>Asserts the four classification outcomes: a fresh matching approval → {@link
 * LivePromotionStatus#VALID}; a 31-day-old approval against a now−30d floor → {@link
 * LivePromotionStatus#STALE}; a different {@code broker_target} → {@link
 * LivePromotionStatus#ABSENT}; a different tenant/strategy → {@link LivePromotionStatus#ABSENT}.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class AuditQueryLivePromotionIT {

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  private static final String RUNTIME_PASSWORD = "it-test-pw";

  private static Connection adminConn;
  private static Connection runtimeConn;
  private static LivePromotionActivitiesImpl livePromotion;
  private static AuditQueryActivitiesImpl auditQuery;

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
    runtimeConn.setAutoCommit(true);

    ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());
    DSLContext dsl = DSL.using(runtimeConn, SQLDialect.POSTGRES);
    AuditLogChainWriter writer = new AuditLogChainWriter(om);
    AuditActivitiesImpl audit = new AuditActivitiesImpl(dsl, om, writer, true, event -> {});
    livePromotion = new LivePromotionActivitiesImpl(audit);
    auditQuery = new AuditQueryActivitiesImpl(dsl);
  }

  @AfterAll
  static void closeDb() throws Exception {
    if (runtimeConn != null) runtimeConn.close();
    if (adminConn != null) adminConn.close();
  }

  @BeforeEach
  void cleanAuditLog() throws Exception {
    try (Statement st = adminConn.createStatement()) {
      st.executeUpdate("DELETE FROM audit_log");
    }
  }

  private void seedApproval(String tenant, String strategy, String brokerTarget) {
    LivePromotionApprovalRequest req = new LivePromotionApprovalRequest();
    req.setSchemaVersion(1L);
    req.setApproverId1("alice");
    req.setApproverId2("bob");
    req.setTenantId(tenant);
    req.setStrategyId(strategy);
    req.setBrokerTarget(brokerTarget);
    livePromotion.approve(req);
  }

  @Test
  void matchesFreshApproval_returnsValid() {
    seedApproval("dev", "copytrade-v1", "alpaca-live");

    OffsetDateTime notStaleSince = OffsetDateTime.now(ZoneOffset.UTC).minusDays(30);
    LivePromotionStatus status =
        auditQuery.checkLivePromotion("dev", "copytrade-v1", "alpaca-live", notStaleSince);

    assertThat(status).isEqualTo(LivePromotionStatus.VALID);
  }

  @Test
  void staleApproval_returnsStale() throws Exception {
    seedApproval("dev", "copytrade-v1", "alpaca-live");
    // Backdate the seeded approval to 31 days ago so it sits before the now−30d staleness floor.
    try (Statement st = adminConn.createStatement()) {
      st.executeUpdate(
          "UPDATE audit_log SET occurred_at = now() - interval '31 days' "
              + "WHERE kind = 'LivePromotionApproved'");
    }

    OffsetDateTime notStaleSince = OffsetDateTime.now(ZoneOffset.UTC).minusDays(30);
    LivePromotionStatus status =
        auditQuery.checkLivePromotion("dev", "copytrade-v1", "alpaca-live", notStaleSince);

    assertThat(status).isEqualTo(LivePromotionStatus.STALE);
  }

  @Test
  void differentBrokerTarget_returnsAbsent() {
    // An approval for tradier-live must not satisfy an alpaca-live verify.
    seedApproval("dev", "copytrade-v1", "tradier-live");

    OffsetDateTime notStaleSince = OffsetDateTime.now(ZoneOffset.UTC).minusDays(30);
    LivePromotionStatus status =
        auditQuery.checkLivePromotion("dev", "copytrade-v1", "alpaca-live", notStaleSince);

    assertThat(status).isEqualTo(LivePromotionStatus.ABSENT);
  }

  @Test
  void otherTenantOrStrategy_returnsAbsent() {
    // Approval exists for (other, other-strategy) — must not satisfy a (dev, copytrade-v1) verify.
    seedApproval("other", "other-strategy", "alpaca-live");

    OffsetDateTime notStaleSince = OffsetDateTime.now(ZoneOffset.UTC).minusDays(30);
    assertThat(auditQuery.checkLivePromotion("dev", "copytrade-v1", "alpaca-live", notStaleSince))
        .isEqualTo(LivePromotionStatus.ABSENT);
  }
}
