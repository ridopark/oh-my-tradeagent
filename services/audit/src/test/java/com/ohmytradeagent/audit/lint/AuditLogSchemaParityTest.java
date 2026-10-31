package com.ohmytradeagent.audit.lint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Docker-free guard — fails the build at {@code mvn test} time when {@code
 * audit_log.correlation_id} is narrower than {@code workflow_id} in the orchestrator migrations, or
 * when the {@code services/audit} IT mirror schema drifts from the orchestrator's effective widths
 * for any of the six VARCHAR columns.
 *
 * <p>Issue #1029: {@code FloorBreachAlertLoop} copies the {@code PositionWorkflow} id (up to 219
 * chars) into {@code correlation_id}. The column was declared {@code VARCHAR(96)} in V2, so every
 * insert for a Discord-derived id (111 chars) silently failed, losing the page. The fix is V12
 * widening {@code correlation_id} to 256 — the same width as {@code workflow_id}. This test fails
 * before V12 (96 ≠ 256) and passes after.
 *
 * <p>The test is purely structural: it parses SQL on disk (no database required). The six checked
 * columns are all VARCHAR columns shared by the orchestrator V2 CREATE TABLE and the audit-svc IT
 * mirror V1 — widening any of them in one place without the other trips the mirror-drift assertion.
 *
 * <p>Skipped when the orchestrator migration directory is absent (partial checkout), consistent
 * with {@link KindRegistryGuardTest}.
 */
class AuditLogSchemaParityTest {

  /** The six VARCHAR columns whose widths must match between orchestrator and IT mirror. */
  private static final List<String> CHECKED_COLUMNS =
      List.of("tenant_id", "strategy_id", "kind", "actor", "workflow_id", "correlation_id");

  /**
   * Matches a column declaration inside a CREATE TABLE: {@code column_name VARCHAR(n)}. The {@code
   * \b} word boundary prevents matching a longer name that starts with the same prefix.
   */
  private static final Pattern CREATE_COLUMN_WIDTH =
      Pattern.compile("\\b(\\w+)\\s+VARCHAR\\((\\d+)\\)", Pattern.CASE_INSENSITIVE);

  /**
   * Matches an entire {@code ALTER TABLE [schema.]audit_log … ;} statement. Group 1 captures the
   * body between the table name reference and the terminating semicolon, which is then scanned for
   * per-column {@code ALTER COLUMN} clauses.
   */
  private static final Pattern ALTER_TABLE_AUDIT_LOG_STMT =
      Pattern.compile(
          "ALTER\\s+TABLE\\s+(?:\\w+\\.)?audit_log\\b([^;]+);",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  /**
   * Within an {@code ALTER TABLE audit_log} body, matches each {@code ALTER COLUMN col [SET DATA]
   * TYPE type} clause. Group 1 = column name; group 2 = raw type text up to the next comma or end
   * of the body.
   */
  private static final Pattern ALTER_COLUMN_CLAUSE =
      Pattern.compile(
          "ALTER\\s+COLUMN\\s+(\\w+)\\s+(?:SET\\s+DATA\\s+)?TYPE\\s+([^,]+)",
          Pattern.CASE_INSENSITIVE);

  /**
   * Matches a bounded {@code VARCHAR(n)} or {@code CHARACTER VARYING(n)} type (full string match).
   * Group 1 = the numeric width.
   */
  private static final Pattern VARCHAR_BOUNDED =
      Pattern.compile(
          "(?:VARCHAR|CHARACTER\\s+VARYING)\\s*\\((\\d+)\\)\\s*", Pattern.CASE_INSENSITIVE);

  @Test
  void correlationIdMatchesWorkflowIdWidthAndMirrorMatchesOrchestrator() throws IOException {
    Path monorepoRoot = findMonorepoRoot();
    Path migrationDir =
        monorepoRoot.resolve("services/orchestrator/src/main/resources/db/migration");
    Path mirrorFile =
        monorepoRoot.resolve(
            "services/audit/src/test/resources/db/migration/audit-it/V1__audit_log_for_it.sql");

    Assumptions.assumeTrue(
        Files.isDirectory(migrationDir),
        "orchestrator migration directory absent — schema parity check skipped");

    // 1. Compute orchestrator effective widths: V2 CREATE TABLE + every ALTER TABLE from V3+.
    Map<String, Integer> orcWidths = orchestratorEffectiveWidths(migrationDir);

    // 2. Fail if correlation_id is narrower than workflow_id — this is the #1029 regression gate.
    int correlationIdWidth = orcWidths.getOrDefault("correlation_id", 0);
    int workflowIdWidth = orcWidths.getOrDefault("workflow_id", 0);
    assertThat(correlationIdWidth)
        .as(
            "audit_log.correlation_id effective width (%d) must equal workflow_id width (%d)."
                + " Add a migration widening correlation_id to %d (see #1029).",
            correlationIdWidth, workflowIdWidth, workflowIdWidth)
        .isEqualTo(workflowIdWidth);

    // 3. Verify mirror file is present and parse it.
    Assumptions.assumeTrue(
        Files.isRegularFile(mirrorFile), "audit IT mirror file absent — parity check skipped");
    Map<String, Integer> mirrorWidths = parseCreateTableWidths(Files.readString(mirrorFile));

    // 4. Assert mirror is in sync with orchestrator for all six checked columns.
    for (String col : CHECKED_COLUMNS) {
      int orcWidth = orcWidths.getOrDefault(col, 0);
      int mirWidth = mirrorWidths.getOrDefault(col, 0);
      assertThat(mirWidth)
          .as(
              "audit IT mirror V1 width of '%s' (%d) must match orchestrator effective width (%d)."
                  + " Update services/audit/src/test/resources/db/migration/audit-it/"
                  + "V1__audit_log_for_it.sql to keep it hand-aligned.",
              col, mirWidth, orcWidth)
          .isEqualTo(orcWidth);
    }
  }

  // ---------------------------------------------------------------------------
  // Finding 1: sort by parsed integer version, not lexicographic file name
  // ---------------------------------------------------------------------------

  /**
   * When migration file names include two-or-more-digit version numbers (V10, V11, V12 …),
   * lexicographic file-name sort puts them before single-digit versions (V3, V4 …) because {@code
   * '1' < '3'}. The effective-width computation must use Flyway's integer version order so a V10
   * widening is not silently overwritten by a lexicographically later V3 narrowing.
   *
   * <p><b>RED before fix:</b> lexicographic sort processes V10 before V3, so V3's 128 overwrites
   * V10's 256 → effective width = 128 ≠ 256 → assertion fails. <b>GREEN after:</b> integer sort
   * processes V3 (3) before V10 (10), so V10's 256 is the final value → 256.
   */
  @Test
  void orchestratorEffectiveWidths_sortsByParsedVersion(@TempDir Path tempDir) throws IOException {
    Files.writeString(
        tempDir.resolve("V2__audit_log.sql"),
        "CREATE TABLE audit_log (correlation_id VARCHAR(96), workflow_id VARCHAR(256));");
    Files.writeString(
        tempDir.resolve("V10__widen.sql"),
        "ALTER TABLE audit_log ALTER COLUMN correlation_id TYPE VARCHAR(256);");
    Files.writeString(
        tempDir.resolve("V3__narrow.sql"),
        "ALTER TABLE audit_log ALTER COLUMN correlation_id TYPE VARCHAR(128);");

    Map<String, Integer> widths = orchestratorEffectiveWidths(tempDir);
    assertThat(widths.get("correlation_id"))
        .as(
            "Integer version order: V3 (3) < V10 (10), so V10 is applied last and the effective"
                + " width must be 256, not 128 (which lexicographic file-name sort produces).")
        .isEqualTo(256);
  }

  // ---------------------------------------------------------------------------
  // Finding 2: ALTER column-type parser — each accepted and rejected form
  // ---------------------------------------------------------------------------

  /**
   * {@code ALTER COLUMN col SET DATA TYPE VARCHAR(n)} — the {@code SET DATA} keyword is optional in
   * standard SQL and valid in Postgres; the parser must accept it.
   *
   * <p><b>RED before fix:</b> the former regex did not include {@code SET DATA}, so this form was
   * silently skipped and the width stayed at 96 instead of 512.
   */
  @Test
  void alterColumn_setDataType_parsedCorrectly(@TempDir Path tempDir) throws IOException {
    Files.writeString(
        tempDir.resolve("V2__audit_log.sql"),
        "CREATE TABLE audit_log (correlation_id VARCHAR(96), workflow_id VARCHAR(256));");
    Files.writeString(
        tempDir.resolve("V3__widen.sql"),
        "ALTER TABLE audit_log ALTER COLUMN correlation_id SET DATA TYPE VARCHAR(512);");

    Map<String, Integer> widths = orchestratorEffectiveWidths(tempDir);
    assertThat(widths.get("correlation_id"))
        .as("SET DATA TYPE VARCHAR(512) must be parsed; width must be 512")
        .isEqualTo(512);
  }

  /**
   * {@code ALTER TABLE public.audit_log …} — a schema-qualified table name is valid Postgres and
   * must be matched the same as the unqualified form.
   *
   * <p><b>RED before fix:</b> the former regex required exactly {@code audit_log} with no schema
   * prefix, so {@code public.audit_log} was silently skipped and the width stayed at 96.
   */
  @Test
  void alterColumn_schemaQualifiedTable_parsedCorrectly(@TempDir Path tempDir) throws IOException {
    Files.writeString(
        tempDir.resolve("V2__audit_log.sql"),
        "CREATE TABLE audit_log (correlation_id VARCHAR(96), workflow_id VARCHAR(256));");
    Files.writeString(
        tempDir.resolve("V3__widen.sql"),
        "ALTER TABLE public.audit_log ALTER COLUMN correlation_id TYPE VARCHAR(512);");

    Map<String, Integer> widths = orchestratorEffectiveWidths(tempDir);
    assertThat(widths.get("correlation_id"))
        .as("Schema-qualified public.audit_log must be matched; width must be 512")
        .isEqualTo(512);
  }

  /**
   * {@code TYPE TEXT} — an unbounded text column; the parser must treat it as unbounded (i.e.
   * {@link Integer#MAX_VALUE}) rather than silently skipping it.
   *
   * <p><b>RED before fix:</b> the former regex required {@code VARCHAR(\d+)} so {@code TEXT} was
   * not matched and the width stayed at 96.
   */
  @Test
  void alterColumn_textType_treatedAsUnbounded(@TempDir Path tempDir) throws IOException {
    Files.writeString(
        tempDir.resolve("V2__audit_log.sql"),
        "CREATE TABLE audit_log (correlation_id VARCHAR(96), workflow_id VARCHAR(256));");
    Files.writeString(
        tempDir.resolve("V3__widen.sql"),
        "ALTER TABLE audit_log ALTER COLUMN correlation_id TYPE TEXT;");

    Map<String, Integer> widths = orchestratorEffectiveWidths(tempDir);
    assertThat(widths.get("correlation_id"))
        .as("TYPE TEXT must be treated as unbounded (Integer.MAX_VALUE)")
        .isEqualTo(Integer.MAX_VALUE);
  }

  /**
   * Two {@code ALTER COLUMN} clauses in a single {@code ALTER TABLE audit_log} statement — both
   * must be applied to the effective widths.
   *
   * <p><b>RED before fix:</b> the former regex required each match to start with {@code ALTER TABLE
   * audit_log ALTER COLUMN}, so only the first clause was matched; the second clause (which starts
   * with just {@code ALTER COLUMN}) was silently ignored and the second column's width stayed at
   * its V2 value.
   */
  @Test
  void alterColumn_multiClause_bothColumnsUpdated(@TempDir Path tempDir) throws IOException {
    Files.writeString(
        tempDir.resolve("V2__audit_log.sql"),
        "CREATE TABLE audit_log (correlation_id VARCHAR(96), workflow_id VARCHAR(96));");
    Files.writeString(
        tempDir.resolve("V3__widen.sql"),
        "ALTER TABLE audit_log"
            + " ALTER COLUMN correlation_id TYPE VARCHAR(256),"
            + " ALTER COLUMN workflow_id TYPE VARCHAR(512);");

    Map<String, Integer> widths = orchestratorEffectiveWidths(tempDir);
    assertThat(widths.get("correlation_id"))
        .as("First clause must update correlation_id to 256")
        .isEqualTo(256);
    assertThat(widths.get("workflow_id"))
        .as("Second clause must update workflow_id to 512 (multi-clause must be parsed)")
        .isEqualTo(512);
  }

  /**
   * A commented-out {@code ALTER TABLE audit_log} statement must be ignored — the comment is not a
   * real migration and must not change any effective width.
   *
   * <p><b>RED before fix:</b> the former regex was applied to raw SQL before comment stripping, so
   * the commented-out ALTER was matched and the width was incorrectly set to 512 instead of staying
   * at 96.
   */
  @Test
  void alterColumn_commentedOut_ignored(@TempDir Path tempDir) throws IOException {
    Files.writeString(
        tempDir.resolve("V2__audit_log.sql"),
        "CREATE TABLE audit_log (correlation_id VARCHAR(96), workflow_id VARCHAR(256));");
    Files.writeString(
        tempDir.resolve("V3__comment.sql"),
        "-- ALTER TABLE audit_log ALTER COLUMN correlation_id TYPE VARCHAR(512);\n"
            + "-- The above is intentionally commented out.\n");

    Map<String, Integer> widths = orchestratorEffectiveWidths(tempDir);
    assertThat(widths.get("correlation_id"))
        .as("Commented-out ALTER must be ignored; width must stay at V2's 96")
        .isEqualTo(96);
  }

  /**
   * An {@code ALTER TABLE audit_log ALTER COLUMN col TYPE <unrecognized>} with a type the parser
   * cannot interpret must fail the test with a clear message — never keep the old width silently.
   *
   * <p><b>RED before fix:</b> the former regex required {@code VARCHAR(\d+)} so an unrecognized
   * type like {@code BIGINT} was silently skipped; no assertion failed and the caller had no idea
   * the tracked column's type had changed.
   */
  @Test
  void alterColumn_unparseableType_failsWithClearMessage(@TempDir Path tempDir) throws IOException {
    Files.writeString(
        tempDir.resolve("V2__audit_log.sql"),
        "CREATE TABLE audit_log (correlation_id VARCHAR(96), workflow_id VARCHAR(256));");
    Files.writeString(
        tempDir.resolve("V3__bad.sql"),
        "ALTER TABLE audit_log ALTER COLUMN correlation_id TYPE BIGINT;");

    assertThatThrownBy(() -> orchestratorEffectiveWidths(tempDir))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("BIGINT");
  }

  // ---------------------------------------------------------------------------
  // Internal helpers
  // ---------------------------------------------------------------------------

  /**
   * Computes effective VARCHAR widths for each column by replaying V2 (CREATE TABLE) then applying
   * any {@code ALTER TABLE [schema.]audit_log ALTER COLUMN col [SET DATA] TYPE …} clauses from V3
   * onward in <em>integer</em> version order (Flyway order). Comments ({@code --} line comments and
   * {@code /* … *\/} block comments) are stripped before matching so commented-out ALTER statements
   * are never applied.
   *
   * <p>Type rules:
   *
   * <ul>
   *   <li>{@code VARCHAR(n)} / {@code CHARACTER VARYING(n)} → {@code n}
   *   <li>{@code TEXT} / unbounded {@code VARCHAR} → {@link Integer#MAX_VALUE}
   *   <li>Any other type on an {@code audit_log} ALTER COLUMN → fail the test with a clear message
   * </ul>
   */
  private static Map<String, Integer> orchestratorEffectiveWidths(Path migrationDir)
      throws IOException {
    Path v2 = migrationDir.resolve("V2__audit_log.sql");
    assertThat(v2)
        .as("V2__audit_log.sql must exist in the orchestrator migration directory")
        .isRegularFile();
    Map<String, Integer> widths = parseCreateTableWidths(Files.readString(v2));

    // Collect V3+ migration files sorted by parsed integer version (Flyway order).
    List<Path> laterFiles = new ArrayList<>();
    try (var stream = Files.list(migrationDir)) {
      stream
          .filter(
              p -> {
                String name = p.getFileName().toString();
                if (!name.matches("V\\d+__.*\\.sql")) {
                  return false;
                }
                int ver = Integer.parseInt(name.substring(1, name.indexOf("__")));
                return ver >= 3;
              })
          .sorted(
              Comparator.comparingInt(
                  (Path p) -> {
                    String name = p.getFileName().toString();
                    return Integer.parseInt(name.substring(1, name.indexOf("__")));
                  }))
          .forEach(laterFiles::add);
    }

    for (Path f : laterFiles) {
      String sql = stripSqlComments(Files.readString(f));
      Matcher stmtMatcher = ALTER_TABLE_AUDIT_LOG_STMT.matcher(sql);
      while (stmtMatcher.find()) {
        String body = stmtMatcher.group(1);
        Matcher clauseMatcher = ALTER_COLUMN_CLAUSE.matcher(body);
        while (clauseMatcher.find()) {
          String col = clauseMatcher.group(1).toLowerCase();
          int colWidth = parseAlterColumnType(col, clauseMatcher.group(2));
          widths.put(col, colWidth);
        }
      }
    }
    return widths;
  }

  /**
   * Strips {@code --} line comments and {@code /* … *\/} block comments from SQL so that
   * commented-out ALTER statements are not mistaken for live migrations.
   */
  private static String stripSqlComments(String sql) {
    String noLineComments =
        sql.lines().map(l -> l.replaceAll("--.*$", "")).collect(Collectors.joining("\n"));
    return noLineComments.replaceAll("(?s)/\\*.*?\\*/", " ");
  }

  /**
   * Parses the raw type string from an {@code ALTER COLUMN col TYPE <type>} clause and returns the
   * declared width:
   *
   * <ul>
   *   <li>{@code VARCHAR(n)} / {@code CHARACTER VARYING(n)} → {@code n}
   *   <li>{@code TEXT} / unbounded {@code VARCHAR} → {@link Integer#MAX_VALUE}
   *   <li>anything else → fails the test with a message naming {@code col} and the raw type
   * </ul>
   */
  private static int parseAlterColumnType(String col, String rawType) {
    String t = rawType.trim();
    Matcher m = VARCHAR_BOUNDED.matcher(t);
    if (m.matches()) {
      return Integer.parseInt(m.group(1));
    }
    if (t.equalsIgnoreCase("TEXT")
        || t.equalsIgnoreCase("VARCHAR")
        || t.matches("(?i)CHARACTER\\s+VARYING")) {
      return Integer.MAX_VALUE;
    }
    assertThat(false)
        .as(
            "ALTER TABLE audit_log ALTER COLUMN %s TYPE %s — unrecognized column type. If this"
                + " migration does not change a tracked VARCHAR column, update the"
                + " ALTER_TABLE_AUDIT_LOG_STMT parser to skip it; if it does, add the type to"
                + " parseAlterColumnType().",
            col, t)
        .isTrue();
    return 0; // unreachable
  }

  /**
   * Extracts {@code column_name → varchar_width} for all {@code VARCHAR(n)} columns appearing in
   * the SQL, stripping comment lines first so a documented example value cannot satisfy the match.
   */
  private static Map<String, Integer> parseCreateTableWidths(String sql) {
    // Strip inline comments before matching, so prose examples don't count.
    String stripped =
        sql.lines().map(line -> line.replaceAll("--.*$", "")).reduce("", (a, b) -> a + "\n" + b);
    Map<String, Integer> widths = new HashMap<>();
    Matcher m = CREATE_COLUMN_WIDTH.matcher(stripped);
    while (m.find()) {
      widths.put(m.group(1).toLowerCase(), Integer.parseInt(m.group(2)));
    }
    return widths;
  }

  /**
   * Walks up from the CWD until the monorepo root is found, mirroring {@link
   * KindRegistryGuardTest}.
   */
  private static Path findMonorepoRoot() {
    Path p = Path.of("").toAbsolutePath();
    while (p != null && p.getParent() != null) {
      if (Files.isDirectory(p.resolve("services"))
          && Files.isDirectory(p.resolve("contract"))
          && Files.isRegularFile(p.resolve("pom.xml"))) {
        return p;
      }
      p = p.getParent();
    }
    return Path.of("").toAbsolutePath();
  }
}
