package com.ohmytradeagent.orchestrator.alert.tradecontext;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Issue #783: the HARD INVARIANT as a build-failing test, the sibling of {@code
 * FloorBreachNoTradingActionGuardTest} for the {@code alert/tradecontext/} package. The recorder is
 * an OBSERVATION-ONLY feature: it must never place, modify, or cancel an order, never
 * signal/update/start a workflow, and — stricter than the floor-breach package, which at least
 * queries workflows — it needs NO Temporal client access at all (everything it records is handed to
 * it by the host loop), and no reference to the broker-side journal databases.
 *
 * <p>Why a sibling guard instead of nesting under {@code alert/floorbreach/}: that guard bans the
 * bare lowercase token {@code "exec"}, which this package cannot satisfy — its whole job is issuing
 * SQL through jOOQ's {@code execute}. The compensations here are the two more precise bans: {@code
 * exec_alpaca} (the journal DB names) and {@code WorkflowClient} / both stub forms (no Temporal
 * verbs of any kind).
 */
class TradeContextNoTradingActionGuardTest {

  private static final List<String> FORBIDDEN_TOKENS =
      List.of(
          "placeOrder",
          "cancelOrder",
          ".signal(",
          ".update(",
          "startWorkflow",
          "WorkflowOptions",
          "newWorkflowStub(",
          "newUntypedWorkflowStub(",
          "WorkflowClient",
          "listExecutions",
          "OrderIntent",
          "exec_alpaca");

  @Test
  void tradeContextPackageContainsNoTradingActionOrTemporalClientTokens() throws IOException {
    Path dir = findTradeContextSourceDir();
    assertThat(dir)
        .as("alert/tradecontext source dir must exist — the guard must never silently skip")
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
        .as("expected the tradecontext package to contain source files; 0 means the scan broke")
        .isGreaterThanOrEqualTo(3);
    assertThat(violations)
        .as(
            "HARD INVARIANT (#783): alert/tradecontext/ is OBSERVATION-ONLY and must contain no "
                + "order-placement/cancellation, workflow signal/update/start, Temporal client, or "
                + "journal-DB token. Violations: %s",
            violations)
        .isEmpty();
  }

  private static Path findTradeContextSourceDir() {
    // Surefire runs with cwd = the module dir; fall back to walking up for IDE runners.
    Path relative = Path.of("src/main/java/com/ohmytradeagent/orchestrator/alert/tradecontext");
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
