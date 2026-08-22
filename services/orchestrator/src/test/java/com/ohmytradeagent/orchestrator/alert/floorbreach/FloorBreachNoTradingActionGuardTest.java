package com.ohmytradeagent.orchestrator.alert.floorbreach;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Issue #779 T7: the HARD INVARIANT as a build-failing test, modeled on {@code
 * KindRegistryGuardTest}. The floor-breach alerter is an ALERT-ONLY feature: it must never place,
 * modify, or cancel an order, and must never signal/update/start a workflow. This lint scans every
 * source file under {@code alert/floorbreach/} and fails the build on any forbidden token.
 *
 * <p>Allowed Temporal verbs, for the record: {@code listExecutions} (Visibility read) and {@code
 * newUntypedWorkflowStub(...).query(...)} (read-only query). {@code newWorkflowStub(} (the TYPED
 * stub) is forbidden because a typed stub can signal; {@code newUntypedWorkflowStub(} does not
 * match that token.
 *
 * <p>The token {@code "exec"} (lowercase) additionally bans any exec-service reference AND, as a
 * deliberate side effect, lowercase prose like "executes" — keeping the package's own text honest
 * about what it may not do.
 */
class FloorBreachNoTradingActionGuardTest {

  private static final List<String> FORBIDDEN_TOKENS =
      List.of(
          "placeOrder",
          "cancelOrder",
          ".signal(",
          ".update(",
          "startWorkflow",
          "WorkflowOptions",
          "newWorkflowStub(",
          "OrderIntent",
          "exec");

  @Test
  void floorBreachPackageContainsNoTradingActionTokens() throws IOException {
    Path dir = findFloorBreachSourceDir();
    assertThat(dir)
        .as("alert/floorbreach source dir must exist — the guard must never silently skip")
        .isNotNull();

    List<String> violations = new ArrayList<>();
    int filesScanned = 0;
    try (Stream<Path> files = Files.walk(dir)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
        filesScanned++;
        String content = Files.readString(file);
        for (String token : FORBIDDEN_TOKENS) {
          int idx = content.indexOf(token);
          if (idx >= 0) {
            int line = 1 + (int) content.substring(0, idx).chars().filter(c -> c == '\n').count();
            violations.add(
                file.getFileName() + ":" + line + " contains forbidden token '" + token + "'");
          }
        }
      }
    }

    assertThat(filesScanned)
        .as("expected the floorbreach package to contain source files; 0 means the scan broke")
        .isGreaterThanOrEqualTo(4);
    assertThat(violations)
        .as(
            "HARD INVARIANT (#779): alert/floorbreach/ is ALERT-ONLY and must contain no "
                + "order-placement/cancellation or workflow signal/update/start token. Violations: %s",
            violations)
        .isEmpty();
  }

  private static Path findFloorBreachSourceDir() {
    // Surefire runs with cwd = the module dir; fall back to walking up for IDE runners.
    Path relative = Path.of("src/main/java/com/ohmytradeagent/orchestrator/alert/floorbreach");
    Path p = Path.of("").toAbsolutePath();
    while (p != null) {
      Path candidate = p.resolve(relative);
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
      Path viaModule = p.resolve("services/orchestrator").resolve(relative);
      if (Files.isDirectory(viaModule)) {
        return viaModule;
      }
      p = p.getParent();
    }
    return null;
  }
}
