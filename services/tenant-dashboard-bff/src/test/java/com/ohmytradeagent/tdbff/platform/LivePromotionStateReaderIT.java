package com.ohmytradeagent.tdbff.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.ohmytradeagent.tdbff.platform.LivePromotionStateReader.LivePromotionState;
import com.ohmytradeagent.tdbff.platform.LivePromotionStateReader.State;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
 * SQL-level coverage for {@link LivePromotionStateReader} against a real Postgres. Replicates the
 * audit-kind contract of {@code AuditQueryActivitiesImpl#checkLivePromotion}: newest {@code
 * LivePromotionApproved} for the (tenant, strategy, broker_target), a {@code
 * LivePromotionDeactivated} strictly after it wins, the 30d TTL is the staleness floor, and a fresh
 * re-activation after a deactivation wins. Gated on {@code RUN_DB_ITS=true}; the {@code audit_log}
 * DDL is inlined (the BFF does not own that schema). Reads only {@code occurred_at} — no secret
 * material is in this table.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class LivePromotionStateReaderIT {

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  private static final String TENANT = "acme";
  private static final String STRATEGY = "copytrade-v1";
  private static final String BROKER = "alpaca-live";

  private static Connection conn;
  private static DSLContext dsl;

  private LivePromotionStateReader reader;

  @BeforeAll
  static void initDb() throws Exception {
    conn =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    dsl = DSL.using(conn, SQLDialect.POSTGRES);
    // Mirrors services/orchestrator/.../db/migration/V2__audit_log.sql (minimal columns this reader
    // touches; the BFF does not own that schema).
    dsl.execute(
        "CREATE TABLE audit_log ("
            + "  id BIGSERIAL PRIMARY KEY,"
            + "  schema_version INT NOT NULL,"
            + "  tenant_id VARCHAR(64) NOT NULL,"
            + "  strategy_id VARCHAR(64) NOT NULL,"
            + "  event_id UUID NOT NULL UNIQUE,"
            + "  occurred_at TIMESTAMPTZ NOT NULL,"
            + "  kind VARCHAR(64) NOT NULL,"
            + "  subject JSONB NOT NULL)");
  }

  @AfterAll
  static void closeDb() throws Exception {
    if (conn != null) {
      conn.close();
    }
  }

  @BeforeEach
  void reset() {
    dsl.execute("DELETE FROM audit_log");
    reader = new LivePromotionStateReader(dsl);
  }

  private void seed(String kind, String tenant, String strategy, String broker, String interval) {
    dsl.execute(
        "INSERT INTO audit_log (schema_version, tenant_id, strategy_id, event_id, occurred_at,"
            + " kind, subject) VALUES (1, ?, ?, gen_random_uuid(), now() + interval '"
            + interval
            + "', ?, jsonb_build_object('broker_target', ?::text))",
        tenant,
        strategy,
        kind,
        broker);
  }

  private void seedConfigChange(String tenant, String strategy, String interval, String... keys) {
    String arr =
        java.util.Arrays.stream(keys)
            .map(k -> "\"" + k + "\"")
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    dsl.execute(
        "INSERT INTO audit_log (schema_version, tenant_id, strategy_id, event_id, occurred_at,"
            + " kind, subject) VALUES (1, ?, ?, gen_random_uuid(), now() + interval '"
            + interval
            + "', 'TenantConfigChanged', jsonb_build_object('changed_keys', ?::jsonb))",
        tenant,
        strategy,
        arr);
  }

  private LivePromotionState read() {
    return reader.stateOf(TENANT, STRATEGY, BROKER, OffsetDateTime.now(ZoneOffset.UTC));
  }

  @Test
  void recentApproval_isValid_withExpiryThirtyDaysOut() {
    seed("LivePromotionApproved", TENANT, STRATEGY, BROKER, "0 seconds");

    LivePromotionState st = read();

    assertThat(st.state()).isEqualTo(State.VALID);
    assertThat(st.atRisk()).isFalse();
    assertThat(st.expiresAt())
        .isCloseTo(OffsetDateTime.now(ZoneOffset.UTC).plusDays(30), within(2, ChronoUnit.MINUTES));
  }

  @Test
  void approvalOlderThanTtl_isStale() {
    seed("LivePromotionApproved", TENANT, STRATEGY, BROKER, "-31 days");

    LivePromotionState st = read();

    assertThat(st.state()).isEqualTo(State.STALE);
    assertThat(st.atRisk()).isFalse();
    // STALE still carries the (past) expiry the record computes.
    assertThat(st.expiresAt()).isBefore(OffsetDateTime.now(ZoneOffset.UTC));
  }

  @Test
  void deactivationAfterApproval_isDeactivated() {
    seed("LivePromotionApproved", TENANT, STRATEGY, BROKER, "-1 hour");
    seed("LivePromotionDeactivated", TENANT, STRATEGY, BROKER, "0 seconds");

    assertThat(read().state()).isEqualTo(State.DEACTIVATED);
  }

  @Test
  void noApproval_isAbsent_withNoExpiry() {
    LivePromotionState st = read();

    assertThat(st.state()).isEqualTo(State.ABSENT);
    assertThat(st.expiresAt()).isNull();
    assertThat(st.atRisk()).isFalse();
  }

  @Test
  void reactivationAfterDeactivation_isValidAgain() {
    // older approval, then a deactivation, then a FRESH approval — newest approval wins and the
    // deactivation is no longer strictly-after it.
    seed("LivePromotionApproved", TENANT, STRATEGY, BROKER, "-2 hours");
    seed("LivePromotionDeactivated", TENANT, STRATEGY, BROKER, "-1 hour");
    seed("LivePromotionApproved", TENANT, STRATEGY, BROKER, "0 seconds");

    assertThat(read().state()).isEqualTo(State.VALID);
  }

  @Test
  void approvalWithinAtRiskWindow_flagsAtRisk() {
    // Approved 28 days ago → expires in ~2 days → inside the 3-day at-risk window, still VALID.
    seed("LivePromotionApproved", TENANT, STRATEGY, BROKER, "-28 days");

    LivePromotionState st = read();

    assertThat(st.state()).isEqualTo(State.VALID);
    assertThat(st.atRisk()).isTrue();
  }

  @Test
  void deactivationForDifferentBrokerTarget_doesNotVoidApproval() {
    seed("LivePromotionApproved", TENANT, STRATEGY, BROKER, "-1 hour");
    seed("LivePromotionDeactivated", TENANT, STRATEGY, "tradier-live", "0 seconds");

    assertThat(read().state()).isEqualTo(State.VALID);
  }

  @Test
  void staleApprovalWithLaterDeactivation_isStale_matchingGateOrdering() {
    // The gate checks the STALE floor BEFORE the deactivation probe, so a >30d-old approval that
    // was
    // ALSO later deactivated reports STALE (the gate's actual disposition), not DEACTIVATED.
    seed("LivePromotionApproved", TENANT, STRATEGY, BROKER, "-31 days");
    seed("LivePromotionDeactivated", TENANT, STRATEGY, BROKER, "-1 hour");

    assertThat(read().state()).isEqualTo(State.STALE);
  }

  @Test
  void riskRelevantConfigChangeAfterApproval_isConfigChanged() {
    // A risk-relevant TenantConfigChanged strictly after the approval voids it (the gate refuses
    // live orders) — the dashboard must NOT show VALID.
    //
    // Uses capital_weight, a LIVE exposure key. This previously used daily_loss_threshold, which
    // the orchestrator had already dropped as a dead field — so this test and the orchestrator's
    // AuditQueryLivePromotionIT asserted OPPOSITE answers for the same key, and neither failed
    // because both are RUN_DB_ITS-gated in separate modules. The key here is incidental to the
    // behaviour under test; what matters is that it is genuinely risk-relevant.
    seed("LivePromotionApproved", TENANT, STRATEGY, BROKER, "-1 hour");
    seedConfigChange(TENANT, STRATEGY, "0 seconds", "capital_weight");

    assertThat(read().state()).isEqualTo(State.CONFIG_CHANGED);
  }

  @Test
  void nonRiskConfigChangeAfterApproval_staysValid() {
    // A config change touching only non-risk keys does NOT void the promotion.
    seed("LivePromotionApproved", TENANT, STRATEGY, BROKER, "0 seconds");
    seedConfigChange(TENANT, STRATEGY, "1 second", "some_cosmetic_label");

    assertThat(read().state()).isEqualTo(State.VALID);
  }

  @Test
  void riskConfigChangeBeforeApproval_doesNotVoid() {
    // Only a change strictly AFTER the matched approval voids it.
    seedConfigChange(TENANT, STRATEGY, "-2 hours", "capital_weight");
    seed("LivePromotionApproved", TENANT, STRATEGY, BROKER, "-1 hour");

    assertThat(read().state()).isEqualTo(State.VALID);
  }
}
