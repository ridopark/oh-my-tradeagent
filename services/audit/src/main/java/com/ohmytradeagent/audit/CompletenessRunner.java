package com.ohmytradeagent.audit;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Runs the completeness check over one window and reports an exit code.
 *
 * <p>Extracted from {@code AuditCompletenessApplication} so the multi-pair aggregation is unit
 * testable without a Spring context: the rule "one failing pair fails the night" is the whole point
 * of the nightly job, and it should not be provable only by running the CronJob.
 *
 * <p>Single constructor on purpose — a {@code @Component} with two unannotated constructors aborts
 * context refresh, which this repo has shipped twice ({@code SpringComponentConstructorGuardTest}).
 */
@Component
public class CompletenessRunner {

  /** Exit codes: 0 = every pair passed, 1 = at least one pair diverged. */
  static final int EXIT_PASS = 0;

  static final int EXIT_DIVERGED = 1;

  private final AuditCompletenessVerifier verifier;
  private final AuditPairSource pairSource;

  public CompletenessRunner(AuditCompletenessVerifier verifier, AuditPairSource pairSource) {
    this.verifier = verifier;
    this.pairSource = pairSource;
  }

  /**
   * Verify {@code [from, to)}. When {@code tenantId}/{@code strategyId} are null the pairs are
   * DISCOVERED from the window's own data — the mode the CronJob uses, so a new tenant is covered
   * without editing a manifest. When both are given, only that pair runs (the original CLI
   * contract, still used for ad-hoc investigation).
   *
   * @return an exit code; {@link #EXIT_DIVERGED} if ANY pair diverged
   */
  public int run(String tenantId, String strategyId, LocalDate from, LocalDate to) {
    OffsetDateTime fromTs = from.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
    OffsetDateTime toTs = to.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();

    List<AuditPairSource.TenantStrategy> pairs =
        (tenantId != null && strategyId != null)
            ? List.of(new AuditPairSource.TenantStrategy(tenantId, strategyId))
            : pairSource.pairsInWindow(fromTs, toTs);

    if (pairs.isEmpty()) {
      // A genuinely empty window (weekend, holiday, cluster down for the day). Matches the
      // documented zero-lifecycle rule: no activity must not break the streak.
      System.out.printf("audit_completeness from=%s to=%s pairs=0 result=PASS%n", from, to);
      return EXIT_PASS;
    }

    int exitCode = EXIT_PASS;
    int errored = 0;
    for (AuditPairSource.TenantStrategy pair : pairs) {
      AuditCompletenessVerifier.Report report;
      try {
        report = verifier.verify(pair.tenantId(), pair.strategyId(), fromTs, toTs);
      } catch (RuntimeException e) {
        // #854 review: a pair that THROWS must not abort the rest. The fail-closed
        // OpenPositionSource raises on a Temporal blip, and letting that propagate meant one
        // unlucky tenant hid real divergences on every pair after it — the exact opposite of why
        // this loop continues past a scored FAIL. Errored is distinct from FAIL: nothing was
        // judged, so nothing can be claimed about that pair's ledger.
        System.out.printf(
            "audit_completeness tenant=%s strategy=%s from=%s to=%s result=ERROR detail=%s%n",
            pair.tenantId(), pair.strategyId(), from, to, e.getMessage());
        errored++;
        exitCode = EXIT_DIVERGED;
        continue;
      }
      System.out.printf(
          "audit_completeness tenant=%s strategy=%s from=%s to=%s events=%d settled_lifecycles=%d "
              + "complete=%d open_carried_forward=%d score=%s divergences=%d result=%s%n",
          report.tenantId(),
          report.strategyId(),
          from,
          to,
          report.totalEvents(),
          report.totalLifecycles(),
          report.completeLifecycles(),
          report.openLifecycles(),
          // A window whose only lifecycles are still open has NOTHING settled to score. Printing
          // "100.00%" beside "result=FAIL" (possible when an open lifecycle also has a genuine
          // missing partial-exit fill) reads as a contradiction to an operator scanning the streak,
          // so the ratio is reported as n/a rather than as a vacuous 100%.
          report.totalLifecycles() == 0 ? "n/a" : String.format("%.2f%%", report.score()),
          report.divergences().size(),
          report.passed() ? "PASS" : "FAIL");
      if (!report.passed()) {
        for (Divergence d : report.divergences()) {
          System.out.printf(
              "  divergence tenant=%s strategy=%s kind=%s correlation_id=%s detail=%s%n",
              report.tenantId(), report.strategyId(), d.kind(), d.correlationId(), d.detail());
        }
        // One bad pair fails the night. Continue the loop anyway so the operator sees EVERY
        // affected pair in one run log instead of fixing them one nightly run at a time.
        exitCode = EXIT_DIVERGED;
      }
    }
    System.out.printf(
        "audit_completeness from=%s to=%s pairs=%d errored=%d result=%s%n",
        from, to, pairs.size(), errored, exitCode == EXIT_PASS ? "PASS" : "FAIL");
    return exitCode;
  }
}
