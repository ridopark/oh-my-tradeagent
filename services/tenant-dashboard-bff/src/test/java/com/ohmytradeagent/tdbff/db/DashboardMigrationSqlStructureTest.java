package com.ohmytradeagent.tdbff.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Structural lock on the V4/V5 dashboard migrations that runs WITHOUT Docker (surefire), so the
 * least-privilege invariant is enforced on every {@code mvn test} even where Testcontainers can't
 * start. The behavioral proof against a real Postgres lives in {@link DashboardWriterMigrationIT}
 * (gated on {@code RUN_DB_ITS}); this asserts the SQL the migration WILL apply, comment lines
 * removed so prose can't satisfy a match.
 */
class DashboardMigrationSqlStructureTest {

  private static String executableSql(String resource) throws IOException {
    try (InputStream in = DashboardMigrationSqlStructureTest.class.getResourceAsStream(resource)) {
      assertThat(in).as("migration resource %s on classpath", resource).isNotNull();
      String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      // Drop full-line SQL comments so assertions match only real DDL, not the doc header.
      return Arrays.stream(raw.split("\n"))
          .map(line -> line.replaceAll("--.*$", ""))
          .collect(Collectors.joining("\n"));
    }
  }

  private static String privilegesGrantedOn(String sql, String table) {
    // \b after the table name means dashboard_user does NOT match dashboard_user_invite.
    Matcher m =
        Pattern.compile("GRANT\\s+([A-Z, ]+?)\\s+ON\\s+" + table + "\\b\\s+TO\\s+dashboard_writer")
            .matcher(sql);
    assertThat(m.find()).as("a GRANT ... ON %s TO dashboard_writer exists", table).isTrue();
    return m.group(1).trim();
  }

  @Test
  void v4CreatesInviteTableWithSingleUseAndExpiryAndOpenInviteUniqueness() throws IOException {
    String sql = executableSql("/db/dashboard/V4__dashboard_user_invite.sql");

    assertThat(sql).contains("CREATE TABLE dashboard_user_invite");
    assertThat(sql).contains("gen_random_uuid()"); // PG13+ core (dashboard DB is postgres:16)
    // Single-use + time-boxed columns the bind flow depends on.
    assertThat(sql).contains("expires_at");
    assertThat(sql).contains("consumed_at");
    assertThat(sql).contains("consumed_provider");
    assertThat(sql).contains("consumed_subject");
    // At most one OPEN invite per (email, tenant), case-insensitive.
    assertThat(sql)
        .containsPattern(
            Pattern.compile(
                "CREATE UNIQUE INDEX.*\\(lower\\(email\\), tenant_id\\)\\s*WHERE consumed_at IS NULL",
                Pattern.DOTALL));
    // Login-time case-insensitive lookup index.
    assertThat(sql)
        .containsPattern(Pattern.compile("CREATE INDEX.*\\(lower\\(email\\)\\)", Pattern.DOTALL));
  }

  @Test
  void v5CreatesWriterRoleIdempotentlyWithPlaceholderPasswordNoLiteral() throws IOException {
    String sql = executableSql("/db/dashboard/V5__dashboard_writer_role.sql");

    // Cluster-global idempotency: guarded CREATE ROLE (no CREATE ROLE IF NOT EXISTS in Postgres).
    assertThat(sql)
        .containsPattern(
            Pattern.compile(
                "IF NOT EXISTS \\(SELECT 1 FROM pg_roles WHERE rolname = 'dashboard_writer'\\)"));
    assertThat(sql).contains("CREATE ROLE dashboard_writer LOGIN INHERIT PASSWORD");
    // Password is the Flyway placeholder, never a repo-readable literal.
    assertThat(sql).contains("PASSWORD '${dashboard_writer_password}'");
    assertThat(sql)
        .as("no literal password: the only PASSWORD value is the placeholder")
        .doesNotContainPattern(
            Pattern.compile("PASSWORD\\s+'(?!\\$\\{dashboard_writer_password\\})"));
  }

  @Test
  void v5GrantsAreExactlyLeastPrivilege() throws IOException {
    String sql = executableSql("/db/dashboard/V5__dashboard_writer_role.sql");

    // Connect + schema usage (else the role can't reach the table).
    assertThat(sql).containsPattern(Pattern.compile("GRANT CONNECT ON DATABASE.*dashboard_writer"));
    assertThat(sql).contains("GRANT USAGE ON SCHEMA public TO dashboard_writer");

    // dashboard_user: INSERT ONLY (bind is INSERT ... ON CONFLICT DO NOTHING; no read-back).
    assertThat(privilegesGrantedOn(sql, "dashboard_user")).isEqualTo("INSERT");
    // dashboard_user_invite: create + read-back + consume, but never delete.
    assertThat(privilegesGrantedOn(sql, "dashboard_user_invite"))
        .isEqualTo("SELECT, INSERT, UPDATE");

    // Hard negative invariants — the whole point of the least-privilege role. Assert the real
    // invariant (DELETE is never a GRANTed privilege) rather than a blunt substring, so a future
    // legit token containing "DELETE" (a column name, a block comment) can't false-fail this.
    assertThat(sql)
        .as("writer never gets DELETE as a granted privilege")
        .doesNotContainPattern(
            Pattern.compile("GRANT\\s+[A-Z, ]*DELETE", Pattern.CASE_INSENSITIVE));
    assertThat(sql)
        .as("V5 must not widen or even reference dashboard_readonly (C7)")
        .doesNotContain("dashboard_readonly");
  }

  @Test
  void v7GrantsDeleteToWriterOnBothIdentityTables_andNothingElse() throws IOException {
    String sql = executableSql("/db/dashboard/V7__grant_delete_dashboard_writer.sql");

    // The Phase 3 teardown grant: DELETE on BOTH identity tables to the writer, in one combined
    // GRANT naming both tables (order-insensitive) so the operator delete can remove a dark
    // tenant's
    // members + open invites.
    assertThat(sql)
        .as("V7 grants DELETE on both identity tables to dashboard_writer")
        .containsPattern(
            Pattern.compile(
                "GRANT\\s+DELETE\\s+ON\\s+dashboard_user\\s*,\\s*dashboard_user_invite"
                    + "\\s+TO\\s+dashboard_writer"));
    // Additive only: V7 grants DELETE and nothing else, and never touches dashboard_readonly (it
    // stays strictly SELECT-only — a live-tenant delete has no path through the readonly role).
    assertThat(sql)
        .as("V7 grants only DELETE (never widens SELECT/INSERT/UPDATE)")
        .doesNotContainPattern(
            Pattern.compile("GRANT\\s+[A-Z, ]*(SELECT|INSERT|UPDATE)", Pattern.CASE_INSENSITIVE));
    assertThat(sql)
        .as("V7 must not reference dashboard_readonly")
        .doesNotContain("dashboard_readonly");
  }
}
