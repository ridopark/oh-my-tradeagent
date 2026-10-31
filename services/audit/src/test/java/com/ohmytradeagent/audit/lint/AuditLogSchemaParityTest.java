package com.ohmytradeagent.audit.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

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
   * Matches {@code ALTER TABLE audit_log ALTER COLUMN col TYPE VARCHAR(n)} in any Flyway migration
   * file. Applied in version order to compute the effective width after all migrations.
   */
  private static final Pattern ALTER_COLUMN_WIDTH =
      Pattern.compile(
          "ALTER\\s+TABLE\\s+audit_log\\s+ALTER\\s+COLUMN\\s+(\\w+)\\s+TYPE\\s+VARCHAR\\((\\d+)\\)",
          Pattern.CASE_INSENSITIVE);

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

  /**
   * Computes effective VARCHAR widths for each column by replaying V2 (CREATE TABLE) then applying
   * any {@code ALTER TABLE audit_log ALTER COLUMN col TYPE VARCHAR(n)} from V3 onward in file-name
   * order.
   */
  private static Map<String, Integer> orchestratorEffectiveWidths(Path migrationDir)
      throws IOException {
    Path v2 = migrationDir.resolve("V2__audit_log.sql");
    assertThat(v2)
        .as("V2__audit_log.sql must exist in the orchestrator migration directory")
        .isRegularFile();
    Map<String, Integer> widths = parseCreateTableWidths(Files.readString(v2));

    // Collect V3+ migration files sorted by file name (Flyway version order).
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
          .sorted()
          .forEach(laterFiles::add);
    }

    for (Path f : laterFiles) {
      String sql = Files.readString(f);
      Matcher m = ALTER_COLUMN_WIDTH.matcher(sql);
      while (m.find()) {
        widths.put(m.group(1).toLowerCase(), Integer.parseInt(m.group(2)));
      }
    }
    return widths;
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
