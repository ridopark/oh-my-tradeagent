package com.ohmytradeagent.tdbff.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ohmytradeagent.tdbff.security.DashboardMembershipReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
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
 * Seam K: covers {@link DashboardMembershipReader}'s SQL query and the {@code dashboard_readonly}
 * role's privilege boundary on the real {@code dashboard_user} table.
 *
 * <ul>
 *   <li>{@code isMember_trueForExistingRow_falseOtherwise} — confirms the SELECT query returns the
 *       right rows (true when the triple exists, false when it does not, false for a different
 *       tenant).
 *   <li>{@code readonlyRole_cannotUpdateDashboardUser} — proves the PG16 column privilege is
 *       SELECT-only: the role that runs the BFF membership query cannot UPDATE the same table.
 * </ul>
 *
 * <p>Runs V1–V16 via Flyway (the production migration sequence; no extra SQL is applied). Flyway
 * connects as the superuser; the membership reader connects as {@code dashboard_readonly} with the
 * test password below. Gated on {@code RUN_DB_ITS=true}; runs under {@code mvn verify} (failsafe).
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class DashboardMembershipReaderIT {

  private static final String READONLY_PW = "readonly-test-pw";
  private static final String WRITER_PW = "writer-test-pw";
  private static final String TC_WRITER_PW = "tc-writer-test-pw";
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
                "dashboard_writer_password", WRITER_PW,
                "trade_context_writer_password", TC_WRITER_PW))
        .load()
        .migrate();
  }

  @BeforeAll
  static void seedDashboardUser() throws SQLException {
    // Insert two rows as the superuser: one that the reader should find, one it should not.
    try (Connection c = asSuperuser();
        var st = c.createStatement()) {
      st.executeUpdate(
          "INSERT INTO dashboard_user (provider, subject, email, tenant_id) "
              + "VALUES ('google', 'sub-1', 'user@example.com', 'acme')");
      // A second row for a different tenant — must NOT match a query for 'acme'.
      st.executeUpdate(
          "INSERT INTO dashboard_user (provider, subject, email, tenant_id) "
              + "VALUES ('google', 'sub-1', 'user@example.com', 'beta')");
    }
  }

  @Test
  void isMember_trueForExistingRow_falseOtherwise() throws SQLException {
    DashboardMembershipReader reader = readerAsReadonly();

    // Exact triple exists → true.
    assertThat(reader.isMember("google", "sub-1", "acme")).isTrue();
    // Different tenant — same sub, different tenant_id → false.
    assertThat(reader.isMember("google", "sub-1", "other-tenant")).isFalse();
    // Different subject → false.
    assertThat(reader.isMember("google", "sub-not-exist", "acme")).isFalse();
    // Different provider → false.
    assertThat(reader.isMember("github", "sub-1", "acme")).isFalse();
  }

  @Test
  void readonlyRole_cannotUpdateDashboardUser() throws SQLException {
    // The SELECT grant lets isMember() work; there must be no UPDATE or DELETE privilege.
    try (Connection c =
            DriverManager.getConnection(postgres.getJdbcUrl(), "dashboard_readonly", READONLY_PW);
        var st = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  st.executeUpdate(
                      "UPDATE dashboard_user SET email = 'x@y.com' WHERE tenant_id = 'acme'"))
          .hasMessageContaining(INSUFFICIENT_PRIVILEGE);
    }
  }

  // ---------------------------------------------------------------------------

  private static DashboardMembershipReader readerAsReadonly() {
    String url = postgres.getJdbcUrl();
    com.zaxxer.hikari.HikariDataSource ds = new com.zaxxer.hikari.HikariDataSource();
    ds.setJdbcUrl(url);
    ds.setUsername("dashboard_readonly");
    ds.setPassword(READONLY_PW);
    ds.setMaximumPoolSize(2);
    return new DashboardMembershipReader(DSL.using(ds, SQLDialect.POSTGRES));
  }

  private static Connection asSuperuser() throws SQLException {
    return DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }
}
