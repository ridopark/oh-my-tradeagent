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
 * Real-Postgres coverage for the {@code dashboard} DB migrations V4 (dashboard_user_invite) + V5
 * (dashboard_writer role). Drives Flyway directly (the app never runs this migration via Spring)
 * with the two role-password placeholders, then asserts:
 *
 * <ul>
 *   <li>the invite table + its two indexes exist; the {@code dashboard_writer} role exists;
 *   <li>LEAST PRIVILEGE (the whole point of V5): {@code dashboard_writer} can INSERT dashboard_user
 *       and SELECT/INSERT/UPDATE dashboard_user_invite, but is DENIED UPDATE/DELETE/SELECT on
 *       dashboard_user, DENIED DELETE on the invite table, and DENIED on any other table;
 *   <li>{@code dashboard_readonly} stays SELECT-only: SELECT on dashboard_user (V2) and on
 *       dashboard_user_invite (V6, for the operator admin listing), but NO write on either.
 * </ul>
 *
 * Gated on {@code RUN_DB_ITS=true} like the module's other DB ITs; runs under {@code verify}
 * (failsafe). Testcontainers connects as the superuser, so Flyway can CREATE ROLE + GRANT.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class DashboardWriterMigrationIT {

  private static final String READONLY_PW = "readonly-test-pw";
  private static final String WRITER_PW = "writer-test-pw";
  private static final String INSUFFICIENT_PRIVILEGE = "42501";

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/dashboard")
        .placeholders(
            Map.of(
                "dashboard_readonly_password", READONLY_PW,
                "dashboard_writer_password", WRITER_PW))
        .load()
        .migrate();
  }

  private static Connection asSuperuser() throws SQLException {
    return DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  private static Connection asRole(String role, String password) throws SQLException {
    // Same JDBC URL (same DB) but a different login role, to exercise the actual GRANTs.
    return DriverManager.getConnection(postgres.getJdbcUrl(), role, password);
  }

  @Test
  void inviteTableAndIndexesAndWriterRoleExist() throws SQLException {
    try (Connection c = asSuperuser();
        var st = c.createStatement()) {
      try (var rs =
          st.executeQuery("SELECT to_regclass('public.dashboard_user_invite') IS NOT NULL")) {
        rs.next();
        assertThat(rs.getBoolean(1)).as("dashboard_user_invite table").isTrue();
      }
      assertThat(indexExists(c, "dashboard_user_invite_open_uidx")).isTrue();
      assertThat(indexExists(c, "dashboard_user_invite_email_idx")).isTrue();
      try (var rs = st.executeQuery("SELECT 1 FROM pg_roles WHERE rolname = 'dashboard_writer'")) {
        assertThat(rs.next()).as("dashboard_writer role").isTrue();
      }
    }
  }

  @Test
  void writerCanInsertDashboardUser_butCannotUpdateDeleteOrSelectIt() throws SQLException {
    try (Connection w = asRole("dashboard_writer", WRITER_PW);
        var st = w.createStatement()) {
      st.executeUpdate(
          "INSERT INTO dashboard_user (provider, subject, email, tenant_id) "
              + "VALUES ('google', 'sub-writer-1', 'a@b.com', 'acme')");

      assertDenied(() -> st.executeUpdate("UPDATE dashboard_user SET email = 'x@y.com'"));
      assertDenied(() -> st.executeUpdate("DELETE FROM dashboard_user"));
      assertDenied(() -> st.executeQuery("SELECT provider FROM dashboard_user"));
    }
  }

  @Test
  void writerCanCrudInvitesExceptDelete() throws SQLException {
    try (Connection w = asRole("dashboard_writer", WRITER_PW);
        var st = w.createStatement()) {
      st.executeUpdate(
          "INSERT INTO dashboard_user_invite (email, tenant_id, created_by, expires_at) "
              + "VALUES ('invitee@x.com', 'acme', 'operator@x.com', now() + interval '7 days')");

      try (var rs = st.executeQuery("SELECT count(*) FROM dashboard_user_invite")) {
        rs.next();
        assertThat(rs.getInt(1)).isEqualTo(1);
      }
      // Consume-style UPDATE is granted.
      assertThat(
              st.executeUpdate(
                  "UPDATE dashboard_user_invite SET consumed_at = now(), "
                      + "consumed_provider = 'google', consumed_subject = 'sub-1' "
                      + "WHERE consumed_at IS NULL"))
          .isEqualTo(1);

      assertDenied(() -> st.executeUpdate("DELETE FROM dashboard_user_invite"));
    }
  }

  @Test
  void writerCannotTouchAnotherTable() throws SQLException {
    // flyway_schema_history exists and was never granted to dashboard_writer.
    try (Connection w = asRole("dashboard_writer", WRITER_PW);
        var st = w.createStatement()) {
      assertDenied(() -> st.executeQuery("SELECT * FROM flyway_schema_history"));
    }
  }

  @Test
  void readonlyIsSelectOnlyOnDashboardUserAndInvite() throws SQLException {
    try (Connection r = asRole("dashboard_readonly", READONLY_PW);
        var st = r.createStatement()) {
      // Still SELECT-able (V2 grant, untouched by V4/V5).
      try (var rs = st.executeQuery("SELECT count(*) FROM dashboard_user")) {
        assertThat(rs.next()).isTrue();
      }
      // No write to dashboard_user.
      assertDenied(
          () ->
              st.executeUpdate(
                  "INSERT INTO dashboard_user (provider, subject, tenant_id) "
                      + "VALUES ('google', 'ro', 'acme')"));
      // V6: readonly may now SELECT the invite table (the operator admin page reads pending
      // invites), but STILL cannot write it — least-privilege preserved.
      try (var rs = st.executeQuery("SELECT count(*) FROM dashboard_user_invite")) {
        assertThat(rs.next()).isTrue();
      }
      assertDenied(
          () ->
              st.executeUpdate(
                  "INSERT INTO dashboard_user_invite (email, tenant_id, created_by, expires_at) "
                      + "VALUES ('e@x.com', 'acme', 'op', now())"));
    }
  }

  private static boolean indexExists(Connection c, String indexName) throws SQLException {
    try (var ps = c.prepareStatement("SELECT 1 FROM pg_indexes WHERE indexname = ?")) {
      ps.setString(1, indexName);
      try (var rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  private static void assertDenied(ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOf(SQLException.class)
        .satisfies(
            e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
  }
}
