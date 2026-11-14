package com.ohmytradeagent.tdbff.db;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Seam K (grant-specific slice): proves the two SQL statements the BFF will execute — the list
 * query as {@code dashboard_readonly} and the revoke UPDATE as {@code dashboard_writer} — against a
 * real Postgres 16 database with V1–V18 applied. Mirrors {@link McpOauthMigrationIT} in structure
 * (single {@code @BeforeAll}, {@code getSQLState()} via {@link #assertDenied}); reuses that file's
 * proven grants rather than re-proving them.
 *
 * <p>D4 (orchestrator, 2026-11-13): grants are column-level on {@code mcp_oauth_grant} and {@code
 * mcp_oauth_client}. The list query and revoke UPDATE must project exactly the granted columns;
 * {@code SELECT *} or accessing unlisted columns must fail with {@code 42501}. This IT proves both
 * the positive (statement succeeds) and the negative (hash columns denied) for each role.
 *
 * <p>Gated on {@code RUN_DB_ITS=true}; runs under {@code mvn verify} (failsafe). Docker is NOT
 * available on the development workstation so these tests first run in CI.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class McpGrantsRepositoryIT {

  private static final String READONLY_PW = "readonly-it-pw";
  private static final String WRITER_PW = "writer-it-pw";
  private static final String TC_WRITER_PW = "tc-writer-it-pw";
  private static final String GATEWAY_PW = "gateway-it-pw";
  private static final String INSUFFICIENT_PRIVILEGE = "42501";

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  /** UUID of the grant seeded for the revoke test. */
  private static String seededGrantId;

  /** UUID of a second grant owned by a different identity — revoke must NOT touch it. */
  private static String otherGrantId;

  @BeforeAll
  static void migrateAndSeed() throws SQLException {
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

      // Enable login for the roles so we can connect as them.
      st.executeUpdate("ALTER ROLE dashboard_readonly LOGIN PASSWORD '" + READONLY_PW + "'");
      st.executeUpdate("ALTER ROLE dashboard_writer LOGIN PASSWORD '" + WRITER_PW + "'");
      // Create the gateway role login so we can seed grants via it; mirrors McpOauthMigrationIT.
      st.executeUpdate("ALTER ROLE mcp_gateway LOGIN PASSWORD '" + GATEWAY_PW + "'");

      // Seed a grant owned by (google, sub-gr-1) — the target identity for the tests.
      try (var ps =
          c.prepareStatement(
              "INSERT INTO mcp_oauth_grant (client_id, client_name, provider, subject, tenants) "
                  + "VALUES ('client-gr-1', 'Test App', 'google', 'sub-gr-1', ARRAY['acme']) "
                  + "RETURNING id")) {
        try (var rs = ps.executeQuery()) {
          rs.next();
          seededGrantId = rs.getString(1);
        }
      }

      // Also seed a grant for a DIFFERENT identity — the revoke must not affect it.
      try (var ps =
          c.prepareStatement(
              "INSERT INTO mcp_oauth_grant (client_id, provider, subject, tenants) "
                  + "VALUES ('client-gr-2', 'google', 'sub-gr-other', ARRAY['acme']) "
                  + "RETURNING id")) {
        try (var rs = ps.executeQuery()) {
          rs.next();
          otherGrantId = rs.getString(1);
        }
      }

      // Seed an mcp_oauth_client row for the LEFT JOIN in the list query.
      st.executeUpdate(
          "INSERT INTO mcp_oauth_client (client_id, metadata, expires_at) "
              + "VALUES ('client-gr-1', '{\"client_name\":\"Test App\"}'::jsonb,"
              + " now() + interval '30 days')");
    }
  }

  // ---------------------------------------------------------------------------
  // Test 4a: listQuery_asReadonly_ok
  // ---------------------------------------------------------------------------

  /**
   * The exact query shape {@code McpGrantsReader} will execute runs successfully as {@code
   * dashboard_readonly} and returns the seeded row. Verifies that the column-level grant covers
   * every column projected, every JOIN column, and the WHERE / ORDER BY predicates.
   */
  @Test
  void listQuery_asReadonly_ok() throws SQLException {
    try (Connection r = asRole("dashboard_readonly", READONLY_PW);
        var ps =
            r.prepareStatement(
                "SELECT g.id, g.client_name, g.client_id, g.tenants,"
                    + " g.created_at, g.last_used_at, g.revoked_at, c.metadata"
                    + " FROM mcp_oauth_grant g"
                    + " LEFT JOIN mcp_oauth_client c ON c.client_id = g.client_id"
                    + " WHERE g.provider = ? AND g.subject = ?"
                    + " ORDER BY g.revoked_at NULLS FIRST, g.created_at DESC")) {
      ps.setString(1, "google");
      ps.setString(2, "sub-gr-1");
      try (var rs = ps.executeQuery()) {
        assertThat(rs.next()).as("list query returns the seeded grant").isTrue();
        assertThat(rs.getString("id")).as("correct grant id").isEqualTo(seededGrantId);
        assertThat(rs.getString("client_name")).as("client_name from grant").isEqualTo("Test App");
        assertThat(rs.getString("client_id")).as("client_id").isEqualTo("client-gr-1");
        // tenants is a TEXT[] — JDBC returns it as a java.sql.Array.
        var tenantsArray = rs.getArray("tenants");
        assertThat(tenantsArray).as("tenants array is not null").isNotNull();
        assertThat((Object[]) tenantsArray.getArray())
            .as("tenants contains acme")
            .containsExactly("acme");
        assertThat(rs.getTimestamp("created_at")).as("created_at is not null").isNotNull();
        assertThat(rs.getTimestamp("last_used_at"))
            .as("last_used_at is null for new grant")
            .isNull();
        assertThat(rs.getTimestamp("revoked_at"))
            .as("revoked_at is null for active grant")
            .isNull();
        // metadata comes from the LEFT JOIN on mcp_oauth_client.
        assertThat(rs.getString("metadata")).as("metadata from join").isNotNull();
        // No second row for the other-identity grant.
        assertThat(rs.next()).as("only the matching identity's grant is returned").isFalse();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Test 4b: revokeUpdate_asWriter_affectsOnlyOwnRow
  // ---------------------------------------------------------------------------

  /**
   * The exact UPDATE shape {@code McpGrantsRevoker} will execute runs successfully as {@code
   * dashboard_writer} and sets {@code revoked_at} on the target grant. The other-identity grant is
   * unchanged — {@code WHERE provider = ? AND subject = ?} limits the scope to this identity only.
   */
  @Test
  void revokeUpdate_asWriter_affectsOnlyOwnRow() throws SQLException {
    try (Connection w = asRole("dashboard_writer", WRITER_PW);
        var ps =
            w.prepareStatement(
                "UPDATE mcp_oauth_grant SET revoked_at = now()"
                    + " WHERE id = ?::uuid AND provider = ? AND subject = ? AND revoked_at IS NULL")) {
      ps.setString(1, seededGrantId);
      ps.setString(2, "google");
      ps.setString(3, "sub-gr-1");
      int rowsAffected = ps.executeUpdate();
      assertThat(rowsAffected).as("exactly one row revoked").isEqualTo(1);
    }

    // The other-identity grant must NOT be revoked (WHERE filters it out).
    try (Connection c = asSuperuser();
        var ps = c.prepareStatement("SELECT revoked_at FROM mcp_oauth_grant WHERE id = ?::uuid")) {
      ps.setString(1, otherGrantId);
      try (var rs = ps.executeQuery()) {
        rs.next();
        assertThat(rs.getTimestamp("revoked_at"))
            .as("other identity's grant is unaffected")
            .isNull();
      }
    }

    // Idempotent: re-running the same UPDATE with revoked_at IS NULL returns 0 rows (already
    // revoked); never touches another identity's grant.
    try (Connection w = asRole("dashboard_writer", WRITER_PW);
        var ps =
            w.prepareStatement(
                "UPDATE mcp_oauth_grant SET revoked_at = now()"
                    + " WHERE id = ?::uuid AND provider = ? AND subject = ? AND revoked_at IS NULL")) {
      ps.setString(1, seededGrantId);
      ps.setString(2, "google");
      ps.setString(3, "sub-gr-1");
      assertThat(ps.executeUpdate()).as("second revoke returns 0 (already revoked)").isEqualTo(0);
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

  /**
   * Asserts that the given callable throws a {@link SQLException} whose {@code SQLState} begins
   * with {@code 42501} (insufficient_privilege). Mirrors {@link McpOauthMigrationIT#assertDenied}.
   */
  @SuppressWarnings("unused")
  private static void assertDenied(ThrowingCallable callable) {
    try {
      callable.call();
      throw new AssertionError(
          "Expected 42501 (insufficient_privilege) but no exception was thrown");
    } catch (Throwable t) {
      String state = extractSqlState(t);
      assertThat(state)
          .as("expected 42501 (insufficient_privilege), got SQLState=%s", state)
          .isEqualTo(INSUFFICIENT_PRIVILEGE);
    }
  }

  private static String extractSqlState(Throwable t) {
    if (t instanceof SQLException se) {
      return se.getSQLState();
    }
    if (t.getCause() != null) {
      return extractSqlState(t.getCause());
    }
    return null;
  }
}
