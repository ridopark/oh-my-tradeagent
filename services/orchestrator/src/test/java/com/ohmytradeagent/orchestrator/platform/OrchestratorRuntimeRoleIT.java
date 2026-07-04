package com.ohmytradeagent.orchestrator.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.util.PSQLException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Issue #84: verifies that the {@code orchestrator_runtime} login role provisioned by {@code
 * V4__orchestrator_runtime_role.sql} can INSERT into {@code audit_log} but is denied UPDATE /
 * DELETE / TRUNCATE by the V3 REVOKE on {@code orchestrator_app}. This is the runtime-enforcement
 * test that {@code docs/ops/audit-retention.md §4} previously called out as a follow-up: without a
 * non-superuser login role, the V3 grant posture is bypassed.
 *
 * <p>Gated on {@code RUN_DB_ITS=true} to match {@code DailyPnlActivitiesImplIT}'s convention —
 * Testcontainers requires Docker, which CI runners may not provide.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class OrchestratorRuntimeRoleIT {

  // Postgres 16 matches the homelab image and the existing DailyPnlActivitiesImplIT IT.
  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  // Match what the operator runs in production (PR body step 3): set the runtime password to
  // a known value after the V4 migration creates the role with placeholder __SET_BY_OPERATOR__.
  private static final String RUNTIME_PASSWORD = "it-test-pw";

  private static Connection adminConn;
  private static Connection runtimeConn;

  @BeforeAll
  static void initDb() throws Exception {
    // 1. Run V1..V4 as the container's default superuser. Flyway in production runs as
    //    `temporal` (also superuser-class); the container's `test` superuser is functionally
    //    equivalent.
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();

    // 2. Open the admin connection and patch the runtime password to a known value, mirroring
    //    the operator step documented in the PR body. V4 created the role with placeholder
    //    `__SET_BY_OPERATOR__`; we set it to RUNTIME_PASSWORD so the runtime connection below
    //    can log in.
    adminConn =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    try (Statement st = adminConn.createStatement()) {
      st.execute("ALTER ROLE orchestrator_runtime PASSWORD '" + RUNTIME_PASSWORD + "'");
    }

    // 3. Open the runtime connection — this exercises the actual production code path.
    runtimeConn =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), "orchestrator_runtime", RUNTIME_PASSWORD);
  }

  @AfterAll
  static void closeDb() throws Exception {
    if (runtimeConn != null) runtimeConn.close();
    if (adminConn != null) adminConn.close();
  }

  @Test
  void runtimeRole_canInsertAuditLog() throws Exception {
    insertOneAuditRowAsRuntime();
    // Read back via admin to confirm the INSERT actually committed (the runtime role has
    // SELECT through orchestrator_app, but reading via admin avoids ambiguity if SELECT
    // ever regresses separately).
    try (Statement st = adminConn.createStatement();
        var rs = st.executeQuery("SELECT count(*) FROM audit_log")) {
      rs.next();
      assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(1);
    }
  }

  @Test
  void runtimeRole_cannotUpdateAuditLog() throws Exception {
    long id = insertOneAuditRowAsRuntime();

    assertThatThrownBy(
            () -> {
              try (Statement st = runtimeConn.createStatement()) {
                st.executeUpdate("UPDATE audit_log SET kind = 'tampered' WHERE id = " + id);
              }
            })
        .isInstanceOf(PSQLException.class)
        .satisfies(e -> assertThat(((PSQLException) e).getSQLState()).isEqualTo("42501"));
  }

  @Test
  void runtimeRole_cannotDeleteAuditLog() throws Exception {
    long id = insertOneAuditRowAsRuntime();

    assertThatThrownBy(
            () -> {
              try (Statement st = runtimeConn.createStatement()) {
                st.executeUpdate("DELETE FROM audit_log WHERE id = " + id);
              }
            })
        .isInstanceOf(PSQLException.class)
        .satisfies(e -> assertThat(((PSQLException) e).getSQLState()).isEqualTo("42501"));
  }

  @Test
  void runtimeRole_canDeleteStrategyConfig() throws Exception {
    // V7__strategy_config_delete_grant.sql grants DELETE on strategy_config to orchestrator_runtime
    // for the operator tenant-delete teardown (dual-control-gated). Seed a row as admin, then prove
    // the constrained runtime role can DELETE it — the boundary that shipped denied before V7 and
    // caused the live "permission denied for table strategy_config" cutover failure.
    seedOneStrategyConfigRowAsAdmin("acme", "copytrade-v1");

    try (Statement st = runtimeConn.createStatement()) {
      int deleted =
          st.executeUpdate(
              "DELETE FROM strategy_config "
                  + "WHERE tenant_id = 'acme' AND strategy_id = 'copytrade-v1'");
      assertThat(deleted)
          .as("V7 must grant DELETE on strategy_config to orchestrator_runtime")
          .isEqualTo(1);
    }

    try (Statement st = adminConn.createStatement();
        var rs =
            st.executeQuery(
                "SELECT count(*) FROM strategy_config "
                    + "WHERE tenant_id = 'acme' AND strategy_id = 'copytrade-v1'")) {
      rs.next();
      assertThat(rs.getInt(1)).isZero();
    }
  }

  @Test
  void runtimeRole_cannotTruncateAuditLog() {
    // No insert needed: TRUNCATE is REVOKE'd unconditionally; the V3 grant posture refuses it
    // even on an empty table.
    assertThatThrownBy(
            () -> {
              try (Statement st = runtimeConn.createStatement()) {
                st.execute("TRUNCATE audit_log");
              }
            })
        .isInstanceOf(PSQLException.class)
        .satisfies(e -> assertThat(((PSQLException) e).getSQLState()).isEqualTo("42501"));
  }

  /** Seeds one minimal strategy_config row as the admin (superuser) role. */
  private static void seedOneStrategyConfigRowAsAdmin(String tenantId, String strategyId)
      throws Exception {
    try (var ps =
        adminConn.prepareStatement(
            "INSERT INTO strategy_config "
                + "(tenant_id, strategy_id, schema_version, config, updated_by) "
                + "VALUES (?, ?, ?, ?::jsonb, ?)")) {
      ps.setString(1, tenantId);
      ps.setString(2, strategyId);
      ps.setInt(3, 1);
      ps.setString(4, "{}");
      ps.setString(5, "seed");
      ps.executeUpdate();
    }
  }

  /** Inserts one minimal audit row as the runtime role and returns the generated id. */
  private static long insertOneAuditRowAsRuntime() throws Exception {
    UUID eventId = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.now());
    try (var ps =
        runtimeConn.prepareStatement(
            "INSERT INTO audit_log "
                + "(schema_version, tenant_id, strategy_id, event_id, occurred_at, kind, subject) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb) RETURNING id")) {
      ps.setInt(1, 1);
      ps.setString(2, "dev");
      ps.setString(3, "copytrade-v1");
      ps.setObject(4, eventId);
      ps.setTimestamp(5, now);
      ps.setString(6, "TestEvent");
      ps.setString(7, "{}");
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }
}
