package com.ohmytradeagent.audit.lint;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.audit.AuditEventKinds;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Issue #90 acceptance criterion 3 verbatim: "Adding a new state-changing Activity without its
 * typed audit event fails the CI lint." This test fails the build at {@code mvn verify} time when
 * the orchestrator source emits a {@code KIND_X = "..."} constant whose literal value is not
 * present in {@link AuditEventKinds#ALL_KINDS}.
 *
 * <p>The check is structured, not heuristic: the regex {@code private static final String
 * KIND_<NAME>\s*=\s*"<VALUE>"} matches exactly the convention used by every orchestrator workflow /
 * activity that emits audit events. A new state-changing Activity that follows the convention will
 * be picked up automatically; a new Activity that invents a different declaration form (e.g. an
 * enum) will need to extend this test alongside the audit-events registry — that's intentional,
 * since the registry is the single source of truth for the verifier's classification logic.
 *
 * <p>Two paths are scanned (configurable for tests via {@code AUDIT_LINT_SCAN_ROOTS}):
 *
 * <ul>
 *   <li>{@code services/orchestrator/src/main/java/com/ohmytradeagent/orchestrator/workflows}
 *   <li>{@code services/orchestrator/src/main/java/com/ohmytradeagent/orchestrator/activities}
 * </ul>
 *
 * <p>The test locates these directories relative to the audit-svc module by walking up to the
 * monorepo root (parent of {@code services/}). If the orchestrator source isn't present (e.g. a
 * partial checkout), the test is skipped via {@code assumeTrue} so a CI build that only includes
 * audit-svc still passes — the same dual-mode behavior as the rest of the test infrastructure.
 */
class KindRegistryGuardTest {

  // Matches: private static final String KIND_NAME = "Value";
  // Tolerates whitespace and optional modifiers; rejects multiline (one constant per line is the
  // established convention in every workflow file).
  private static final Pattern KIND_CONSTANT =
      Pattern.compile(
          "private\\s+static\\s+final\\s+String\\s+KIND_[A-Z0-9_]+\\s*=\\s*\"([A-Za-z][A-Za-z0-9]+)\"");

  @Test
  void everyOrchestratorKindConstantIsRegistered() throws IOException {
    Path monorepoRoot = findMonorepoRoot();
    Path workflowsDir =
        monorepoRoot.resolve(
            "services/orchestrator/src/main/java/com/ohmytradeagent/orchestrator/workflows");
    Path activitiesDir =
        monorepoRoot.resolve(
            "services/orchestrator/src/main/java/com/ohmytradeagent/orchestrator/activities");

    org.junit.jupiter.api.Assumptions.assumeTrue(
        Files.isDirectory(workflowsDir) || Files.isDirectory(activitiesDir),
        "orchestrator source not present in this checkout — lint check skipped");

    Set<String> literals = new TreeSet<>();
    if (Files.isDirectory(workflowsDir)) {
      literals.addAll(extractLiterals(workflowsDir));
    }
    if (Files.isDirectory(activitiesDir)) {
      literals.addAll(extractLiterals(activitiesDir));
    }

    assertThat(literals)
        .as(
            "found %d KIND_ constants in orchestrator source; expected the regex to match at "
                + "least a handful — if zero, the regex broke",
            literals.size())
        .isNotEmpty();

    List<String> missing = new ArrayList<>();
    for (String literal : literals) {
      if (!AuditEventKinds.ALL_KINDS.contains(literal)) {
        missing.add(literal);
      }
    }

    assertThat(missing)
        .as(
            "Every KIND_ constant emitted by an orchestrator workflow or activity must be "
                + "registered in AuditEventKinds.ALL_KINDS. Missing entries: %s. "
                + "Add them to AuditEventKinds (with the correct lifecycle group) before the "
                + "audit-completeness verifier can score them.",
            missing)
        .isEmpty();
  }

  private static List<String> extractLiterals(Path dir) throws IOException {
    List<String> literals = new ArrayList<>();
    Files.walkFileTree(
        dir,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (!file.toString().endsWith(".java")) {
              return FileVisitResult.CONTINUE;
            }
            String content = Files.readString(file);
            Matcher m = KIND_CONSTANT.matcher(content);
            while (m.find()) {
              literals.add(m.group(1));
            }
            return FileVisitResult.CONTINUE;
          }
        });
    return literals;
  }

  private static Path findMonorepoRoot() {
    // Start from the working directory and walk up looking for a sibling 'services' folder.
    Path p = Path.of("").toAbsolutePath();
    while (p != null && p.getParent() != null) {
      if (Files.isDirectory(p.resolve("services"))
          && Files.isDirectory(p.resolve("contract"))
          && Files.isRegularFile(p.resolve("pom.xml"))) {
        return p;
      }
      p = p.getParent();
    }
    // Fallback: the cwd itself. Tests will skip via assumeTrue if the orchestrator path doesn't
    // resolve below.
    return Path.of("").toAbsolutePath();
  }
}
