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
  void v7GrantsDeletePlusColumnScopedTenantIdSelect_andNothingElse() throws IOException {
    String sql = executableSql("/db/dashboard/V7__grant_delete_dashboard_writer.sql");

    // The Phase 3 teardown grant: DELETE on BOTH identity tables to the writer, in one combined
    // GRANT naming both tables (order-insensitive) so the operator delete can remove a dark
    // tenant's members + open invites.
    assertThat(sql)
        .as("V7 grants DELETE on both identity tables to dashboard_writer")
        .containsPattern(
            Pattern.compile(
                "GRANT\\s+DELETE\\s+ON\\s+dashboard_user\\s*,\\s*dashboard_user_invite"
                    + "\\s+TO\\s+dashboard_writer"));
    // The ONLY SELECT V7 grants is COLUMN-SCOPED to tenant_id on dashboard_user — required because
    // a
    // tenant-scoped DELETE ... WHERE tenant_id=? must read tenant_id (PG needs SELECT on the column
    // to evaluate the predicate). This keeps PII (provider/subject/email) unreadable to the writer.
    assertThat(sql)
        .as("V7 grants the column-scoped SELECT (tenant_id) the DELETE WHERE requires")
        .containsPattern(
            Pattern.compile(
                "GRANT\\s+SELECT\\s*\\(\\s*tenant_id\\s*\\)\\s+ON\\s+dashboard_user"
                    + "\\s+TO\\s+dashboard_writer"));
    // No TABLE-WIDE SELECT (that would expose PII), and no INSERT/UPDATE widening at all. A
    // table-level SELECT grant is `GRANT SELECT ON ...` (SELECT immediately followed by ON, no
    // column list); the allowed column form is `GRANT SELECT (tenant_id) ON ...`.
    assertThat(sql)
        .as("V7 must not grant table-wide SELECT (would expose PII) nor any INSERT/UPDATE")
        .doesNotContainPattern(
            Pattern.compile(
                "GRANT\\s+[A-Z, ]*(SELECT\\s+ON|INSERT|UPDATE)", Pattern.CASE_INSENSITIVE));
    assertThat(sql)
        .as("V7 must not reference dashboard_readonly")
        .doesNotContain("dashboard_readonly");
  }

  @Test
  void v9CreatesTheThreeOptionsChatTablesWithBigintIdsAndIdentityChildKeys() throws IOException {
    String sql = executableSql("/db/dashboard/V9__options_chat.sql");

    assertThat(sql).contains("CREATE TABLE options_chat_message");
    assertThat(sql).contains("CREATE TABLE options_chat_attachment");
    assertThat(sql).contains("CREATE TABLE options_chat_embed");

    // Snowflakes are BIGINT, never TEXT: a TEXT id sorts lexicographically, which only matches
    // chronological order while every id has the same digit count (Discord crossed 18->19 digits in
    // 2021). Getting this wrong mis-orders rows at a page boundary.
    assertThat(sql).containsPattern(Pattern.compile("message_id\\s+BIGINT\\s+PRIMARY KEY"));
    assertThat(sql).containsPattern(Pattern.compile("channel_id\\s+BIGINT\\s+NOT NULL"));

    // Child PKs use IDENTITY, not BIGSERIAL — a non-owner INSERT into a BIGSERIAL column also needs
    // USAGE on the backing sequence, a grant no existing migration here models and which fails only
    // at runtime. IDENTITY attaches the sequence to the column, removing the failure mode.
    assertThat(sql)
        .as("child tables use GENERATED ALWAYS AS IDENTITY, never BIGSERIAL")
        .doesNotContainIgnoringCase("BIGSERIAL");
    assertThat(sql)
        .containsPattern(
            Pattern.compile(
                "id\\s+BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY.*"
                    + "id\\s+BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY",
                Pattern.DOTALL));

    // The pagination index must match the read cursor exactly, and be NAMED (V1/V4 convention, and
    // the migration IT asserts on index names).
    assertThat(sql)
        .as("named index matching the (channel_id, message_id DESC) read cursor")
        .containsPattern(
            Pattern.compile(
                "CREATE INDEX options_chat_message_channel_id_message_id_idx\\s+"
                    + "ON options_chat_message \\(channel_id, message_id DESC\\)",
                Pattern.DOTALL));

    // Media bytes are nullable — Phase 1 stores descriptors only; Phase 4 fills them in.
    assertThat(sql).containsPattern(Pattern.compile("bytes\\s+BYTEA"));
    assertThat(sql).containsPattern(Pattern.compile("ON DELETE CASCADE"));
  }

  @Test
  void v9GrantsSelectInsertToWriterOnly_neverUpdateOrDelete_neverReadonly() throws IOException {
    String sql = executableSql("/db/dashboard/V9__options_chat.sql");

    // SELECT is load-bearing on the WRITE path here, not just for reads: `ON CONFLICT (message_id)
    // DO NOTHING` reads the arbiter index, and without SELECT that raises 42501 (the same PG rule
    // V7 documents for its DELETE). Exactly what the shipped code issues, and no more.
    assertThat(privilegesGrantedOn(sql, "options_chat_message")).isEqualTo("SELECT, INSERT");
    assertThat(privilegesGrantedOn(sql, "options_chat_attachment")).isEqualTo("SELECT, INSERT");
    assertThat(privilegesGrantedOn(sql, "options_chat_embed")).isEqualTo("SELECT, INSERT");

    // Neither DELETE (Phase 6 retention) nor UPDATE (Phase 4 media fill, Phase 6 edit reconcile).
    // Each widens deliberately in its own migration when the code needing it ships, exactly as V5
    // withheld DELETE until V7 needed it. Granting a privilege nothing uses is how least-privilege
    // rots — and "the next phase will want it" is the argument that starts the rot.
    assertThat(sql)
        .as("V9 never grants DELETE or UPDATE — later phases widen deliberately")
        .doesNotContainPattern(
            Pattern.compile("GRANT\\s+[A-Z, ]*(DELETE|UPDATE)", Pattern.CASE_INSENSITIVE));
    // dashboard_readonly is the browser-facing Next.js pool (dashboard/lib/db.ts). /options-chat is
    // served through the BFF, so granting that role SELECT on untrusted third-party content would
    // widen a Next.js compromise for no benefit.
    assertThat(sql)
        .as("V9 must not grant or even reference dashboard_readonly")
        .doesNotContain("dashboard_readonly");
  }

  @Test
  void v10WidensOnlyAttachmentUpdate_forThePhase4MediaFill() throws IOException {
    String sql = executableSql("/db/dashboard/V10__options_chat_media.sql");

    // Exactly one grant, exactly one table, exactly one privilege: filling in the fetched bytes.
    assertThat(sql)
        .containsPattern(
            Pattern.compile(
                "GRANT\\s+UPDATE\\s+ON\\s+options_chat_attachment\\s+TO\\s+dashboard_writer"));

    // The message and embed tables stay unwidened — Phase 6 owns the edit reconcile, and embeds are
    // replaced with their message rather than mutated.
    assertThat(sql)
        .as("V10 must not widen options_chat_message")
        .doesNotContainPattern(Pattern.compile("GRANT[^;]*options_chat_message"));
    assertThat(sql)
        .as("V10 must not widen options_chat_embed")
        .doesNotContainPattern(Pattern.compile("GRANT[^;]*options_chat_embed"));
    // Retention is still a later, separate decision.
    assertThat(sql)
        .as("V10 never grants DELETE")
        .doesNotContainPattern(
            Pattern.compile("GRANT\\s+[A-Z, ]*DELETE", Pattern.CASE_INSENSITIVE));
    assertThat(sql)
        .as("V10 must not reference dashboard_readonly")
        .doesNotContain("dashboard_readonly");
  }

  @Test
  void v12GrantsDeleteOnTheParentTableOnly_soTheCascadeIsTheOnlyWayChildrenGo() throws IOException {
    String sql = executableSql("/db/dashboard/V12__options_chat_retention.sql");

    assertThat(sql)
        .containsPattern(
            Pattern.compile(
                "GRANT\\s+DELETE\\s+ON\\s+options_chat_message\\s+TO\\s+dashboard_writer"));

    // The blast radius of this grant is "can remove a message and, transitively, its own children",
    // never "can empty the attachment table". The FK cascade does the rest, and V12 relies on that
    // rather than widening further.
    assertThat(sql)
        .as("V12 must not grant DELETE on the child tables")
        .doesNotContainPattern(Pattern.compile("GRANT[^;]*options_chat_attachment"));
    assertThat(sql)
        .as("V12 must not grant DELETE on the child tables")
        .doesNotContainPattern(Pattern.compile("GRANT[^;]*options_chat_embed"));
    assertThat(sql)
        .as("V12 widens DELETE only — never UPDATE or SELECT")
        .doesNotContainPattern(
            Pattern.compile("GRANT\\s+[A-Z, ]*(UPDATE|SELECT)", Pattern.CASE_INSENSITIVE));
    assertThat(sql)
        .as("V12 must not reference dashboard_readonly")
        .doesNotContain("dashboard_readonly");
  }
}
