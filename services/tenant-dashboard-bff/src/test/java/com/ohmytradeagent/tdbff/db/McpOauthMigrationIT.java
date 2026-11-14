package com.ohmytradeagent.tdbff.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Seam K: covers V17 ({@code mcp_oauth_*} tables + grants to existing roles) and V18 ({@code
 * mcp_gateway} role + column-level dashboard_user grant). Mirrors the structure and helpers of
 * {@link DashboardWriterMigrationIT} / {@link DarkpoolMigrationIT}: single {@code @BeforeAll},
 * {@code getSQLState()} assertions via {@link #assertDenied}, one {@link PostgreSQLContainer}
 * shared across all tests.
 *
 * <p>D1 (orchestrator, 2026-11-13): {@code mcp_gateway} is created NOLOGIN in V18 — no password, no
 * Flyway placeholder. The {@code @BeforeAll} asserts the role is NOLOGIN immediately after
 * migration, then does {@code ALTER ROLE mcp_gateway LOGIN PASSWORD} so the privilege tests below
 * can connect. This mirrors the operator enablement step documented in the PR 5 runbook.
 *
 * <p>PG16: {@code WHERE} / {@code ON CONFLICT} columns need {@code SELECT} on the executing role.
 * Tests exercise the gateway's real query shapes so a missing grant fails here — not on the
 * cluster.
 *
 * <p>Gated on {@code RUN_DB_ITS=true}; runs under {@code mvn verify} (failsafe). Docker is NOT
 * available on the development workstation, so these tests first run in CI.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class McpOauthMigrationIT {

  private static final String READONLY_PW = "readonly-test-pw";
  private static final String WRITER_PW = "writer-test-pw";
  private static final String TC_WRITER_PW = "tc-writer-test-pw";
  private static final String GATEWAY_PW = "gateway-test-pw";
  private static final String INSUFFICIENT_PRIVILEGE = "42501";

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  /** Grant id seeded for test 3 (gateway CRUD assertions). */
  private static String seededGrantId;

  /** Separate grant id seeded for test 5 (writer revoke assertion). */
  private static String writerGrantId;

  /**
   * Single {@code @BeforeAll}: V1–V18 migration, then:
   *
   * <ol>
   *   <li>Assert {@code mcp_gateway} is NOLOGIN immediately after V18 (D1).
   *   <li>Alter the role to LOGIN so privilege tests below can connect (operator enablement
   *       mirror).
   *   <li>Seed test data as superuser (dashboard_user, two grants, refresh+access tokens).
   * </ol>
   */
  @BeforeAll
  static void migrateAndWire() throws SQLException {
    // V18 has no mcp_gateway_password placeholder (D1). Only the three existing role passwords.
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/dashboard")
        .placeholders(
            Map.of(
                "dashboard_readonly_password", READONLY_PW,
                "dashboard_writer_password", WRITER_PW,
                "trade_context_writer_password", TC_WRITER_PW))
        .load()
        .migrate();

    try (Connection c = asSuperuser();
        var st = c.createStatement()) {

      // D1 assertion: role exists and is NOLOGIN immediately after V18. Any migration that creates
      // the role with LOGIN (contradicting D1) fails here before any privilege test runs.
      try (var rs =
          st.executeQuery("SELECT rolcanlogin FROM pg_roles WHERE rolname = 'mcp_gateway'")) {
        assertThat(rs.next()).as("mcp_gateway role exists after V18").isTrue();
        assertThat(rs.getBoolean("rolcanlogin"))
            .as("mcp_gateway is NOLOGIN until the operator sets a password (D1)")
            .isFalse();
      }

      // ALTER to LOGIN so the privilege tests below can connect.
      // This mirrors the operator's enablement step documented in the PR 5 runbook.
      st.executeUpdate("ALTER ROLE mcp_gateway LOGIN PASSWORD '" + GATEWAY_PW + "'");

      // Seed a dashboard_user row for the membership read test.
      st.executeUpdate(
          "INSERT INTO dashboard_user (provider, subject, email, tenant_id) "
              + "VALUES ('google', 'sub-gw-1', 'gw@test.com', 'acme')");

      // Seed an mcp_oauth_client row for the ON CONFLICT test (test 3).
      st.executeUpdate(
          "INSERT INTO mcp_oauth_client (client_id, metadata, expires_at) "
              + "VALUES ('test-client-1', '{}'::jsonb, now() + interval '30 days')");

      // Seed grant for test 3 (gateway CRUD), capture the generated id.
      try (var rs =
          st.executeQuery(
              "INSERT INTO mcp_oauth_grant (client_id, provider, subject, tenants) "
                  + "VALUES ('test-client-1', 'google', 'sub-gw-1', ARRAY['acme']) "
                  + "RETURNING id")) {
        rs.next();
        seededGrantId = rs.getString(1);
      }

      // Seed refresh + access tokens referencing the test-3 grant.
      st.executeUpdate(
          "INSERT INTO mcp_oauth_refresh_token (token_hash, grant_id, expires_at) "
              + "VALUES ('rt-hash-1', '"
              + seededGrantId
              + "', now() + interval '30 days')");
      st.executeUpdate(
          "INSERT INTO mcp_oauth_access_token (token_hash, grant_id, expires_at) "
              + "VALUES ('at-hash-1', '"
              + seededGrantId
              + "', now() + interval '1 hour')");

      // Seed a SEPARATE grant for test 5 (writer revoke) so test 3's revoke doesn't interfere.
      try (var rs =
          st.executeQuery(
              "INSERT INTO mcp_oauth_grant (client_id, provider, subject, tenants) "
                  + "VALUES ('test-client-1', 'google', 'sub-gw-1', ARRAY['acme']) "
                  + "RETURNING id")) {
        rs.next();
        writerGrantId = rs.getString(1);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Test 1: tables + indexes + FK constraints
  // ---------------------------------------------------------------------------

  @Test
  void tablesAndIndexesExist() throws SQLException {
    try (Connection c = asSuperuser();
        var st = c.createStatement()) {

      for (String t :
          new String[] {
            "mcp_oauth_client",
            "mcp_oauth_pending_auth",
            "mcp_oauth_grant",
            "mcp_oauth_code",
            "mcp_oauth_refresh_token",
            "mcp_oauth_access_token"
          }) {
        try (var rs = st.executeQuery("SELECT to_regclass('public." + t + "') IS NOT NULL")) {
          rs.next();
          assertThat(rs.getBoolean(1)).as("table %s exists", t).isTrue();
        }
      }

      // (provider, subject) index on mcp_oauth_grant — required for the membership lookup and
      // the Connected-AI-apps listing.
      assertThat(indexExists(c, "mcp_oauth_grant_provider_subject_idx"))
          .as("(provider, subject) index on mcp_oauth_grant")
          .isTrue();

      // FK from child tables to mcp_oauth_grant.
      assertThat(fkExists(c, "mcp_oauth_code", "mcp_oauth_grant"))
          .as("FK mcp_oauth_code → mcp_oauth_grant")
          .isTrue();
      assertThat(fkExists(c, "mcp_oauth_refresh_token", "mcp_oauth_grant"))
          .as("FK mcp_oauth_refresh_token → mcp_oauth_grant")
          .isTrue();
      assertThat(fkExists(c, "mcp_oauth_access_token", "mcp_oauth_grant"))
          .as("FK mcp_oauth_access_token → mcp_oauth_grant")
          .isTrue();
    }
  }

  // ---------------------------------------------------------------------------
  // Test 2: mcp_gateway — column-level SELECT on dashboard_user, nothing else
  // ---------------------------------------------------------------------------

  @Test
  void gatewayRole_canReadMembershipColumnsOnly() throws SQLException {
    try (Connection gw = asRole("mcp_gateway", GATEWAY_PW)) {

      // The real membership query: SELECT (provider, subject, tenant_id) with a WHERE on provider +
      // subject. PG16: the WHERE columns must be in the column grant — the grant includes them.
      try (var ps =
          gw.prepareStatement(
              "SELECT provider, subject, tenant_id FROM dashboard_user "
                  + "WHERE provider = ? AND subject = ?")) {
        ps.setString(1, "google");
        ps.setString(2, "sub-gw-1");
        try (var rs = ps.executeQuery()) {
          assertThat(rs.next()).as("SELECT returns the seeded membership row").isTrue();
          assertThat(rs.getString("tenant_id")).isEqualTo("acme");
        }
      }

      var st = gw.createStatement();

      // email is NOT in the column grant — must be denied.
      assertDenied(() -> st.executeQuery("SELECT email FROM dashboard_user"));

      // Table-wide SELECT (* or any unlisted column) is not granted — column-level grant only.
      assertDenied(() -> st.executeQuery("SELECT * FROM dashboard_user"));

      // No access to dashboard_user_invite at all.
      assertDenied(() -> st.executeQuery("SELECT 1 FROM dashboard_user_invite"));

      // No write to dashboard_user (the gateway only reads memberships, never modifies them).
      assertDenied(
          () ->
              st.executeUpdate(
                  "INSERT INTO dashboard_user (provider, subject, email, tenant_id) "
                      + "VALUES ('google', 'sub-gw-new', 'new@test.com', 'acme')"));
      assertDenied(
          () ->
              st.executeUpdate(
                  "UPDATE dashboard_user SET email = 'x@y.com' WHERE provider = 'google'"));
      assertDenied(() -> st.executeUpdate("DELETE FROM dashboard_user WHERE provider = 'google'"));
    }
  }

  // ---------------------------------------------------------------------------
  // Test 3: mcp_gateway — CRUD on the six OAuth tables
  // ---------------------------------------------------------------------------

  @Test
  void gatewayRole_hasCrudOnOauthTables_includingOnConflictAndUpdateWhere() throws SQLException {
    try (Connection gw = asRole("mcp_gateway", GATEWAY_PW)) {

      // INSERT ... ON CONFLICT (client_id) DO UPDATE: requires SELECT on the conflict arbiter
      // column (PG16). Conflicts with the seeded 'test-client-1' row and bumps last_used_at.
      try (var ps =
          gw.prepareStatement(
              "INSERT INTO mcp_oauth_client (client_id, metadata, expires_at) "
                  + "VALUES (?, ?::jsonb, now() + interval '30 days') "
                  + "ON CONFLICT (client_id) DO UPDATE SET last_used_at = now()")) {
        ps.setString(1, "test-client-1");
        ps.setString(2, "{}");
        ps.executeUpdate(); // must not throw
      }

      // UPDATE mcp_oauth_refresh_token WHERE token_hash = ? AND rotated_at IS NULL.
      try (var ps =
          gw.prepareStatement(
              "UPDATE mcp_oauth_refresh_token SET rotated_at = now(), replaced_by_hash = ? "
                  + "WHERE token_hash = ? AND rotated_at IS NULL")) {
        ps.setString(1, "new-rt-hash");
        ps.setString(2, "rt-hash-1");
        assertThat(ps.executeUpdate()).isEqualTo(1);
      }

      // UPDATE mcp_oauth_grant SET revoked_at (the grant_revocation path).
      try (var ps =
          gw.prepareStatement("UPDATE mcp_oauth_grant SET revoked_at = now() WHERE id = ?::uuid")) {
        ps.setString(1, seededGrantId);
        assertThat(ps.executeUpdate()).isEqualTo(1);
      }

      // INSERT a short-lived client for the DELETE sweep test.
      try (var ps =
          gw.prepareStatement(
              "INSERT INTO mcp_oauth_client (client_id, metadata, expires_at) "
                  + "VALUES (?, ?::jsonb, now() - interval '1 day')")) {
        ps.setString(1, "expired-client");
        ps.setString(2, "{}");
        ps.executeUpdate();
      }

      // DELETE FROM mcp_oauth_client WHERE expires_at < now() AND last_used_at IS NULL
      // (the unused-client sweep).
      try (var ps =
          gw.prepareStatement(
              "DELETE FROM mcp_oauth_client WHERE expires_at < now() AND last_used_at IS NULL")) {
        assertThat(ps.executeUpdate()).isGreaterThanOrEqualTo(1);
      }

      // SELECT with JOIN: access token + grant (the token-verification query).
      // The grant was just revoked so the WHERE filters it out — the test proves the query runs
      // without a privilege error (42501) even when the result set is empty.
      try (var ps =
          gw.prepareStatement(
              "SELECT t.token_hash, t.expires_at FROM mcp_oauth_access_token t "
                  + "JOIN mcp_oauth_grant g ON t.grant_id = g.id "
                  + "WHERE t.token_hash = ? AND g.revoked_at IS NULL")) {
        ps.setString(1, "at-hash-1");
        ps.executeQuery().close(); // no 42501 is the assertion
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Test 4: dashboard_readonly — SELECT on grant + client, nothing else
  // ---------------------------------------------------------------------------

  @Test
  void readonlyRole_canSelectGrantsAndClients_cannotWrite() throws SQLException {
    try (Connection r = asRole("dashboard_readonly", READONLY_PW);
        var st = r.createStatement()) {

      // V17 grants SELECT on mcp_oauth_grant and mcp_oauth_client to dashboard_readonly.
      st.executeQuery("SELECT count(*) FROM mcp_oauth_grant").close();
      st.executeQuery("SELECT count(*) FROM mcp_oauth_client").close();

      // No write on mcp_oauth_grant.
      assertDenied(
          () ->
              st.executeUpdate(
                  "UPDATE mcp_oauth_grant SET revoked_at = now() WHERE revoked_at IS NULL"));

      // No SELECT on mcp_oauth_refresh_token (not in the V17 readonly grant).
      assertDenied(() -> st.executeQuery("SELECT count(*) FROM mcp_oauth_refresh_token"));
    }
  }

  // ---------------------------------------------------------------------------
  // Test 5: dashboard_writer — revoke grant, cannot insert or touch token tables
  // ---------------------------------------------------------------------------

  @Test
  void writerRole_canRevokeGrant_cannotInsertOrTouchTokens() throws SQLException {
    try (Connection w = asRole("dashboard_writer", WRITER_PW)) {

      // PR 4 revoke path: UPDATE WHERE id = ? AND provider = ? AND subject = ? AND revoked_at IS
      // NULL.
      // dashboard_writer has table-level SELECT on mcp_oauth_grant (covers all WHERE columns per
      // PG16).
      try (var ps =
          w.prepareStatement(
              "UPDATE mcp_oauth_grant SET revoked_at = now() "
                  + "WHERE id = ?::uuid AND provider = ? AND subject = ? AND revoked_at IS NULL")) {
        ps.setString(1, writerGrantId);
        ps.setString(2, "google");
        ps.setString(3, "sub-gw-1");
        assertThat(ps.executeUpdate())
            .as("revoke affects exactly the writer's own row")
            .isEqualTo(1);
      }

      var st = w.createStatement();

      // Cannot INSERT into mcp_oauth_grant — writer creates nothing, only revokes.
      assertDenied(
          () ->
              st.executeUpdate(
                  "INSERT INTO mcp_oauth_grant (client_id, provider, subject, tenants) "
                      + "VALUES ('test-client-1', 'google', 'sub-gw-1', ARRAY['acme'])"));

      // Cannot SELECT mcp_oauth_access_token — the writer never touches token tables.
      assertDenied(() -> st.executeQuery("SELECT count(*) FROM mcp_oauth_access_token"));
    }
  }

  // ---------------------------------------------------------------------------
  // Test 6: existing roles are unchanged
  // ---------------------------------------------------------------------------

  @Test
  void existingRoles_unchangedElsewhere() throws SQLException {
    // dashboard_readonly can SELECT dashboard_user_invite (V6) but V17 added no further
    // privileges — UPDATE must still be denied.
    try (Connection r = asRole("dashboard_readonly", READONLY_PW);
        var st = r.createStatement()) {
      st.executeQuery("SELECT count(*) FROM dashboard_user_invite").close();
      assertDenied(() -> st.executeUpdate("UPDATE dashboard_user_invite SET consumed_at = now()"));
    }

    // dashboard_writer has only column-level SELECT (tenant_id) on dashboard_user (V7).
    // V17/V18 must not widen this — PII columns must remain denied.
    try (Connection w = asRole("dashboard_writer", WRITER_PW);
        var st = w.createStatement()) {
      // tenant_id is accessible (the V7 column grant the DELETE WHERE needs).
      try (var rs = st.executeQuery("SELECT tenant_id FROM dashboard_user")) {
        assertThat(rs.next()).as("tenant_id is accessible to dashboard_writer").isTrue();
      }
      // PII columns must stay denied — V17/V18 must not have widened the writer's view.
      assertDenied(() -> st.executeQuery("SELECT email FROM dashboard_user"));
      assertDenied(() -> st.executeQuery("SELECT provider FROM dashboard_user"));
      assertDenied(() -> st.executeQuery("SELECT subject FROM dashboard_user"));
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static Connection asSuperuser() throws SQLException {
    return DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  private static Connection asRole(String role, String password) throws SQLException {
    return DriverManager.getConnection(postgres.getJdbcUrl(), role, password);
  }

  private static boolean indexExists(Connection c, String indexName) throws SQLException {
    try (var ps = c.prepareStatement("SELECT 1 FROM pg_indexes WHERE indexname = ?")) {
      ps.setString(1, indexName);
      try (var rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  /**
   * Returns true when at least one FK constraint exists from {@code fromTable} to {@code toTable}.
   */
  private static boolean fkExists(Connection c, String fromTable, String toTable)
      throws SQLException {
    try (var ps =
        c.prepareStatement(
            "SELECT 1 FROM pg_constraint con "
                + "JOIN pg_class rc ON rc.oid = con.confrelid "
                + "JOIN pg_class fc ON fc.oid = con.conrelid "
                + "WHERE con.contype = 'f' AND fc.relname = ? AND rc.relname = ?")) {
      ps.setString(1, fromTable);
      ps.setString(2, toTable);
      try (var rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  /**
   * Asserts that the action throws a {@link SQLException} with SQLState {@code 42501}
   * (insufficient_privilege). Matches the pattern used by {@link DashboardWriterMigrationIT} and
   * {@link DarkpoolMigrationIT}: checks {@code getSQLState()} not the message text, since pgjdbc
   * keeps the SQLState out of the exception message.
   */
  private static void assertDenied(ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOf(SQLException.class)
        .satisfies(
            e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
  }
}
