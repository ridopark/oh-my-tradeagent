package com.ohmytradeagent.tdbff.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.tdbff.mcp.McpGrantsReader;
import com.ohmytradeagent.tdbff.mcp.McpGrantsReader.McpGrantRow;
import com.ohmytradeagent.tdbff.mcp.McpGrantsRevoker;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs the production {@link McpGrantsReader} and {@link McpGrantsRevoker} against a real Postgres
 * 16 database with V1–V18 applied, each over a connection logged in as the role it uses in
 * production ({@code dashboard_readonly} / {@code dashboard_writer}). This proves the shipped SQL
 * stays inside the column-level grants (plan D4) and that both statements are scoped to the
 * caller's own {@code (provider, subject)}. The grants' denials are proven by {@link
 * McpOauthMigrationIT}.
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
    }
  }

  // ---------------------------------------------------------------------------
  // Test 4a: the production reader, as dashboard_readonly
  // ---------------------------------------------------------------------------

  /**
   * Runs the real {@link McpGrantsReader} over a {@code dashboard_readonly} connection, so both the
   * column grants (D4) and the ownership predicate are exercised by the shipped SQL: dropping
   * {@code WHERE provider/subject} from the reader makes this test fail.
   */
  @Test
  void reader_asReadonly_returnsOnlyTheCallersGrants() throws SQLException {
    try (Connection r = asRole("dashboard_readonly", READONLY_PW)) {
      McpGrantsReader reader = new McpGrantsReader(DSL.using(r, SQLDialect.POSTGRES));

      List<McpGrantRow> mine = reader.listForIdentity("google", "sub-gr-1");
      assertThat(mine).as("exactly the caller's one grant").hasSize(1);
      McpGrantRow row = mine.get(0);
      assertThat(row.id()).isEqualTo(seededGrantId);
      assertThat(row.clientName()).isEqualTo("Test App");
      assertThat(row.clientId()).isEqualTo("client-gr-1");
      assertThat(row.tenants()).containsExactly("acme");
      assertThat(row.createdAt()).isNotNull();
      assertThat(row.lastUsedAt()).isNull();

      assertThat(reader.listForIdentity("google", "sub-gr-other"))
          .extracting(McpGrantRow::id)
          .containsExactly(otherGrantId);
      assertThat(reader.listForIdentity("google", "nobody")).isEmpty();
    }
  }

  // ---------------------------------------------------------------------------
  // Test 4b: the production revoker, as dashboard_writer
  // ---------------------------------------------------------------------------

  /**
   * Runs the real {@link McpGrantsRevoker} over a {@code dashboard_writer} connection. Another
   * identity's grant id is refused (false, row untouched) — dropping {@code provider/subject} from
   * the UPDATE makes this test fail. The caller's own grant is revoked once, then idempotent.
   */
  @Test
  void revoker_asWriter_revokesOnlyTheCallersOwnGrant() throws SQLException {
    try (Connection w = asRole("dashboard_writer", WRITER_PW)) {
      McpGrantsRevoker revoker = new McpGrantsRevoker(DSL.using(w, SQLDialect.POSTGRES));

      assertThat(revoker.revoke(otherGrantId, "google", "sub-gr-1"))
          .as("someone else's grant id is refused")
          .isFalse();
      assertThat(revokedAt(otherGrantId)).as("and that grant is untouched").isNull();

      assertThat(revoker.revoke(seededGrantId, "google", "sub-gr-1")).isTrue();
      assertThat(revokedAt(seededGrantId)).isNotNull();
      assertThat(revokedAt(otherGrantId)).as("other identity's grant still active").isNull();

      assertThat(revoker.revoke(seededGrantId, "google", "sub-gr-1"))
          .as("already revoked")
          .isFalse();
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

  private static java.sql.Timestamp revokedAt(String grantId) throws SQLException {
    try (Connection c = asSuperuser();
        var ps = c.prepareStatement("SELECT revoked_at FROM mcp_oauth_grant WHERE id = ?::uuid")) {
      ps.setString(1, grantId);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getTimestamp(1);
      }
    }
  }
}
