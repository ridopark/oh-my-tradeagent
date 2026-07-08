package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.LiveActivationRequest;
import com.ohmytradeagent.contract.LiveDeactivationRequest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
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
 * AuditQueryActivitiesImpl#checkLivePromotion}, the live-promotion safety-gate verify. Seeds {@code
 * LivePromotionApproved} rows by calling the REAL {@link LivePromotionActivitiesImpl#activate} (the
 * single-operator one-click path) so the JSONB subject shape (incl. {@code broker_target} and
 * {@code occurred_at == approved_at}) is authoritative rather than hand-rolled.
 *
 * <p>Asserts the four classification outcomes: a fresh matching activation → {@link
 * LivePromotionStatus#VALID}; a 31-day-old activation against a now−30d floor → {@link
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
  private static AuditActivitiesImpl audit;
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
    audit = new AuditActivitiesImpl(dsl, om, writer, true, event -> {});
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

  /** Phase F: seed a one-click activation via the REAL activate() (gate-readable approval row). */
  private void seedActivation(String tenant, String strategy, String brokerTarget) {
    LiveActivationRequest req = new LiveActivationRequest();
    req.setSchemaVersion(1L);
    req.setTenantId(tenant);
    req.setStrategyId(strategy);
    req.setBrokerTarget(LiveActivationRequest.BrokerTarget.fromValue(brokerTarget));
    req.setOperatorId("ridopark");
    req.setExpectedAccountId("PA3FKGPFYPLH");
    livePromotion.activate(req);
  }

  /** Phase F: seed a one-click deactivation via the REAL deactivate(). Returns the event_id. */
  private String seedDeactivation(String tenant, String strategy, String brokerTarget) {
    LiveDeactivationRequest req = new LiveDeactivationRequest();
    req.setSchemaVersion(1L);
    req.setTenantId(tenant);
    req.setStrategyId(strategy);
    req.setBrokerTarget(LiveDeactivationRequest.BrokerTarget.fromValue(brokerTarget));
    req.setOperatorId("ridopark");
    livePromotion.deactivate(req);
    // The newest LivePromotionDeactivated row for this triple — its event_id so the caller can
    // force occurred_at relative to the approval.
    org.jooq.Record r =
        DSL.using(runtimeConn, SQLDialect.POSTGRES)
            .fetchOne(
                "SELECT event_id FROM audit_log WHERE tenant_id = ? AND strategy_id = ? "
                    + "AND kind = 'LivePromotionDeactivated' ORDER BY occurred_at DESC LIMIT 1",
                tenant,
                strategy);
    return r == null ? null : r.get(0, String.class);
  }

  /**
   * Seeds one {@code TenantConfigChanged} audit row whose {@code changed_keys} contains exactly
   * {@code changedKey}, emitted through the SAME {@link AuditActivitiesImpl#log} path the
   * TenantConfigChangedEmitter uses (so the JSONB subject shape — including the {@code
   * changed_keys} array — is authoritative). Returns the event_id so the caller can force {@code
   * occurred_at} via the admin-conn UPDATE idiom. The prior/current maps differ only on {@code
   * changedKey} so {@code TenantConfigChangedEvents.diffKeys} yields {@code [changedKey]}.
   */
  private String seedConfigChanged(String tenant, String strategy, String changedKey) {
    Map<String, Object> prior = Map.of(changedKey, "old");
    Map<String, Object> current = Map.of(changedKey, "new");
    AuditEvent event =
        TenantConfigChangedEvents.build(
            tenant,
            strategy,
            "operator:test",
            "configmap-reload",
            null,
            null,
            prior,
            current,
            Set.of());
    audit.log(event);
    return event.getEventId();
  }

  private void forceOccurredAt(String eventId, String interval) throws Exception {
    try (Statement st = adminConn.createStatement()) {
      st.executeUpdate(
          "UPDATE audit_log SET occurred_at = now() + interval '"
              + interval
              + "' WHERE event_id = '"
              + eventId
              + "'");
    }
  }

  @Test
  void matchesFreshApproval_returnsValid() {
    seedActivation("dev", "copytrade-v1", "alpaca-live");

    OffsetDateTime notStaleSince = OffsetDateTime.now(ZoneOffset.UTC).minusDays(30);
    LivePromotionStatus status =
        auditQuery.checkLivePromotion("dev", "copytrade-v1", "alpaca-live", notStaleSince);

    assertThat(status).isEqualTo(LivePromotionStatus.VALID);
  }

  @Test
  void staleApproval_returnsStale() throws Exception {
    seedActivation("dev", "copytrade-v1", "alpaca-live");
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
    seedActivation("dev", "copytrade-v1", "tradier-live");

    OffsetDateTime notStaleSince = OffsetDateTime.now(ZoneOffset.UTC).minusDays(30);
    LivePromotionStatus status =
        auditQuery.checkLivePromotion("dev", "copytrade-v1", "alpaca-live", notStaleSince);

    assertThat(status).isEqualTo(LivePromotionStatus.ABSENT);
  }

  @Test
  void otherTenantOrStrategy_returnsAbsent() {
    // Approval exists for (other, other-strategy) — must not satisfy a (dev, copytrade-v1) verify.
    seedActivation("other", "other-strategy", "alpaca-live");

    OffsetDateTime notStaleSince = OffsetDateTime.now(ZoneOffset.UTC).minusDays(30);
    assertThat(auditQuery.checkLivePromotion("dev", "copytrade-v1", "alpaca-live", notStaleSince))
        .isEqualTo(LivePromotionStatus.ABSENT);
  }

  // --- Phase F: one-click activation / deactivation -------------------------------------------

  @Test
  void activateThenRead_returnsValid() {
    // The single-operator activate() emits the SAME gate-readable LivePromotionApproved kind, so a
    // fresh activation reads VALID exactly like a dual-control approval.
    seedActivation("dev", "copytrade-v1", "alpaca-live");

    OffsetDateTime notStaleSince = OffsetDateTime.now(ZoneOffset.UTC).minusDays(30);
    assertThat(auditQuery.checkLivePromotion("dev", "copytrade-v1", "alpaca-live", notStaleSince))
        .isEqualTo(LivePromotionStatus.VALID);
  }

  @Test
  void deactivationAfterApproval_returnsDeactivated_failClosed() throws Exception {
    // A LivePromotionDeactivated row whose occurred_at is strictly AFTER the matched approval is an
    // explicit operator revocation → fail CLOSED to DEACTIVATED (non-VALID).
    seedActivation("dev", "copytrade-v1", "alpaca-live");
    String deactId = seedDeactivation("dev", "copytrade-v1", "alpaca-live");
    forceOccurredAt(deactId, "1 hour");

    OffsetDateTime notStaleSince = OffsetDateTime.now(ZoneOffset.UTC).minusDays(30);
    assertThat(auditQuery.checkLivePromotion("dev", "copytrade-v1", "alpaca-live", notStaleSince))
        .isEqualTo(LivePromotionStatus.DEACTIVATED);
  }

  @Test
  void deactivationBeforeReactivation_returnsValid() throws Exception {
    // A deactivation followed by a FRESH re-activation (newer approved_at) must NOT void the newer
    // approval — the newest LivePromotionApproved is selected first, and only a deactivation AFTER
    // it counts. Force the deactivation into the past, then re-activate at ~now.
    seedActivation("dev", "copytrade-v1", "alpaca-live");
    String deactId = seedDeactivation("dev", "copytrade-v1", "alpaca-live");
    forceOccurredAt(deactId, "-1 hour");
    seedActivation("dev", "copytrade-v1", "alpaca-live");

    OffsetDateTime notStaleSince = OffsetDateTime.now(ZoneOffset.UTC).minusDays(30);
    assertThat(auditQuery.checkLivePromotion("dev", "copytrade-v1", "alpaca-live", notStaleSince))
        .isEqualTo(LivePromotionStatus.VALID);
  }

  @Test
  void deactivationOtherBrokerTarget_returnsValid() throws Exception {
    // A deactivation scoped to a DIFFERENT broker_target must not void this approval.
    seedActivation("dev", "copytrade-v1", "alpaca-live");
    String deactId = seedDeactivation("dev", "copytrade-v1", "tradier-live");
    forceOccurredAt(deactId, "1 hour");

    OffsetDateTime notStaleSince = OffsetDateTime.now(ZoneOffset.UTC).minusDays(30);
    assertThat(auditQuery.checkLivePromotion("dev", "copytrade-v1", "alpaca-live", notStaleSince))
        .isEqualTo(LivePromotionStatus.VALID);
  }

  // --- P3-b: config-change invalidation -------------------------------------------------------

  @Test
  void riskConfigChangedAfterApproval_returnsConfigChanged() throws Exception {
    // Fresh approval, then a risk-relevant TenantConfigChanged (notional_cap_pct_of_capital_base)
    // whose occurred_at is AFTER the approval → the risk envelope the approvers signed off on no
    // longer holds → CONFIG_CHANGED.
    seedActivation("dev", "copytrade-v1", "alpaca-live");
    String cfgEventId =
        seedConfigChanged("dev", "copytrade-v1", "notional_cap_pct_of_capital_base");
    forceOccurredAt(cfgEventId, "1 hour");

    OffsetDateTime notStaleSince = OffsetDateTime.now(ZoneOffset.UTC).minusDays(30);
    assertThat(auditQuery.checkLivePromotion("dev", "copytrade-v1", "alpaca-live", notStaleSince))
        .isEqualTo(LivePromotionStatus.CONFIG_CHANGED);
  }

  @Test
  void nonRiskConfigChangedAfterApproval_returnsValid() throws Exception {
    // A config change touching only a NON-risk field (max_slippage_pct is not in the void set) must
    // not invalidate the approval → VALID.
    seedActivation("dev", "copytrade-v1", "alpaca-live");
    String cfgEventId = seedConfigChanged("dev", "copytrade-v1", "max_slippage_pct");
    forceOccurredAt(cfgEventId, "1 hour");

    OffsetDateTime notStaleSince = OffsetDateTime.now(ZoneOffset.UTC).minusDays(30);
    assertThat(auditQuery.checkLivePromotion("dev", "copytrade-v1", "alpaca-live", notStaleSince))
        .isEqualTo(LivePromotionStatus.VALID);
  }

  @Test
  void riskConfigChangedBeforeApproval_returnsValid() throws Exception {
    // A risk change that occurred BEFORE the approval is already subsumed by the sign-off (strict
    // >)
    // → VALID. Approval is at ~now; force the config change one hour into the PAST.
    seedActivation("dev", "copytrade-v1", "alpaca-live");
    String cfgEventId =
        seedConfigChanged("dev", "copytrade-v1", "notional_cap_pct_of_capital_base");
    forceOccurredAt(cfgEventId, "-1 hour");

    OffsetDateTime notStaleSince = OffsetDateTime.now(ZoneOffset.UTC).minusDays(30);
    assertThat(auditQuery.checkLivePromotion("dev", "copytrade-v1", "alpaca-live", notStaleSince))
        .isEqualTo(LivePromotionStatus.VALID);
  }

  @Test
  void configChangedOtherTenantOrStrategy_returnsValid() throws Exception {
    // A risk change scoped to a DIFFERENT (tenant, strategy) must not invalidate this approval.
    seedActivation("dev", "copytrade-v1", "alpaca-live");
    String cfgEventId =
        seedConfigChanged("other", "other-strategy", "notional_cap_pct_of_capital_base");
    forceOccurredAt(cfgEventId, "1 hour");

    OffsetDateTime notStaleSince = OffsetDateTime.now(ZoneOffset.UTC).minusDays(30);
    assertThat(auditQuery.checkLivePromotion("dev", "copytrade-v1", "alpaca-live", notStaleSince))
        .isEqualTo(LivePromotionStatus.VALID);
  }

  @Test
  void configChangeQueryDbError_returnsVerifyError() throws Exception {
    // Set up a fresh, valid, non-stale approval so the verify reaches the P3-b 2nd query, then
    // close
    // the runtime connection so that 2nd query throws — fail-CLOSED to VERIFY_ERROR, never VALID.
    seedActivation("dev", "copytrade-v1", "alpaca-live");

    // Use a dedicated runtime connection + DSLContext we can close mid-test without poisoning the
    // shared one used by the other tests in this class.
    java.sql.Connection brokenConn =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), "orchestrator_runtime", RUNTIME_PASSWORD);
    brokenConn.setAutoCommit(true);
    DSLContext brokenDsl = DSL.using(brokenConn, SQLDialect.POSTGRES);
    AuditQueryActivitiesImpl brokenAuditQuery = new AuditQueryActivitiesImpl(brokenDsl);
    brokenConn.close();

    OffsetDateTime notStaleSince = OffsetDateTime.now(ZoneOffset.UTC).minusDays(30);
    assertThat(
            brokenAuditQuery.checkLivePromotion(
                "dev", "copytrade-v1", "alpaca-live", notStaleSince))
        .isEqualTo(LivePromotionStatus.VERIFY_ERROR);
  }
}
