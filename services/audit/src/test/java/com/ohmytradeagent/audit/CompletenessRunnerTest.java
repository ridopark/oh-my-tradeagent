package com.ohmytradeagent.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ohmytradeagent.contract.AuditEvent;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * #853: the CronJob hardcoded {@code TENANT=dev STRATEGY=copytrade-v1}, a pair with zero events, so
 * it scored 100% on an empty set every night while five real tenants — three of them real-money —
 * went unchecked. The pairs are now DISCOVERED from the window's own data.
 *
 * <p>The rule these pin is "one failing pair fails the night", which otherwise would only be
 * provable by running the CronJob and reading its exit code.
 */
class CompletenessRunnerTest {

  private static final LocalDate FROM = LocalDate.of(2026, 9, 11);
  private static final LocalDate TO = LocalDate.of(2026, 9, 12);

  /** The five (tenant, strategy) pairs that actually had activity on 2026-09-11, as measured. */
  private static final List<AuditPairSource.TenantStrategy> REAL_PAIRS =
      List.of(
          new AuditPairSource.TenantStrategy("paper_jinchiul", "copytrade-v1"),
          new AuditPairSource.TenantStrategy("prod-jinchul", "copytrade-v1"),
          new AuditPairSource.TenantStrategy("prod-kipark", "copytrade-v1"),
          new AuditPairSource.TenantStrategy("prod_real", "copytrade-v1"),
          new AuditPairSource.TenantStrategy("staging_paper", "watchlist-trigger-v1"));

  /** Events keyed by "tenant/strategy", so each pair can be given its own ledger. */
  private final Map<String, List<AuditEvent>> eventsByPair = new LinkedHashMap<>();

  private final Set<String> openCorrelations = new java.util.HashSet<>();

  private CompletenessRunner runner(List<AuditPairSource.TenantStrategy> pairs) {
    AuditEventSource source =
        (tenantId, strategyId, from, to) ->
            eventsByPair.getOrDefault(tenantId + "/" + strategyId, List.of());
    OpenPositionSource openPositions = (tenantId, strategyId) -> Set.copyOf(openCorrelations);
    AuditCompletenessVerifier verifier =
        new AuditCompletenessVerifier(source, new LedgerRederiver(), openPositions);
    AuditPairSource pairSource = (from, to) -> pairs;
    return new CompletenessRunner(verifier, pairSource);
  }

  private void givePair(String tenant, String strategy, String corr, boolean closed) {
    List<AuditEvent> events = new ArrayList<>();
    events.add(event(tenant, strategy, corr, "EntryFilled", 0));
    events.add(event(tenant, strategy, corr, "PositionEntered", 1));
    if (closed) {
      events.add(event(tenant, strategy, corr, "PositionClosed", 2));
    }
    eventsByPair.put(tenant + "/" + strategy, events);
  }

  private static AuditEvent event(
      String tenant, String strategy, String corr, String kind, int minute) {
    AuditEvent e = new AuditEvent();
    e.setSchemaVersion(1L);
    e.setTenantId(tenant);
    e.setStrategyId(strategy);
    e.setEventId(UUID.randomUUID().toString());
    e.setOccurredAt(OffsetDateTime.parse("2026-09-11T14:00:00Z").plusMinutes(minute));
    e.setKind(kind);
    e.setSubject(Map.of());
    e.setCorrelationId(corr);
    return e;
  }

  // The regression: every real pair must be verified, not just one hardcoded name.
  @Test
  void discoversAndVerifiesEveryPairWithActivity() {
    for (AuditPairSource.TenantStrategy p : REAL_PAIRS) {
      givePair(p.tenantId(), p.strategyId(), "corr-" + p.tenantId(), true);
    }

    assertThat(runner(REAL_PAIRS).run(null, null, FROM, TO))
        .isEqualTo(CompletenessRunner.EXIT_PASS);
  }

  // One bad pair fails the night, even when it is not the first one scanned — the aggregation must
  // not short-circuit on the first PASS.
  @Test
  void oneDivergentPairFailsTheWholeRun() {
    for (AuditPairSource.TenantStrategy p : REAL_PAIRS) {
      givePair(p.tenantId(), p.strategyId(), "corr-" + p.tenantId(), true);
    }
    // prod_real is 4th of 5 in the ordering, and its close event is missing with no open position.
    givePair("prod_real", "copytrade-v1", "corr-prod_real", false);

    assertThat(runner(REAL_PAIRS).run(null, null, FROM, TO))
        .isEqualTo(CompletenessRunner.EXIT_DIVERGED);
  }

  // An unclosed lifecycle whose position is still open must NOT fail the night — this is the
  // overnight hold that made the job permanently red on real tenants.
  @Test
  void anOpenPositionDoesNotFailTheRun() {
    givePair("prod_real", "copytrade-v1", "corr-open", false);
    openCorrelations.add("corr-open");

    assertThat(
            runner(List.of(new AuditPairSource.TenantStrategy("prod_real", "copytrade-v1")))
                .run(null, null, FROM, TO))
        .isEqualTo(CompletenessRunner.EXIT_PASS);
  }

  // #854 review, the major finding: a pair that THROWS must not abort the rest. The fail-closed
  // OpenPositionSource raises on a Temporal blip, and letting that propagate meant one unlucky
  // tenant hid real divergences on every pair scanned after it.
  @Test
  void aThrowingPairDoesNotAbortTheRemainingPairs() {
    List<AuditPairSource.TenantStrategy> pairs = REAL_PAIRS;
    for (AuditPairSource.TenantStrategy p : pairs) {
      givePair(p.tenantId(), p.strategyId(), "corr-" + p.tenantId(), true);
    }
    // The LAST pair has a genuinely missing close, and an EARLIER pair throws. Before the fix the
    // throw propagated and that last divergence was never reached.
    givePair("staging_paper", "watchlist-trigger-v1", "corr-staging_paper", false);

    List<String> verified = new ArrayList<>();
    AuditEventSource source =
        (tenantId, strategyId, from, to) -> {
          verified.add(tenantId + "/" + strategyId);
          return eventsByPair.getOrDefault(tenantId + "/" + strategyId, List.of());
        };
    OpenPositionSource flaky =
        (tenantId, strategyId) -> {
          if ("prod-kipark".equals(tenantId)) {
            throw new IllegalStateException("Temporal visibility unavailable");
          }
          return Set.of();
        };
    CompletenessRunner runner =
        new CompletenessRunner(
            new AuditCompletenessVerifier(source, new LedgerRederiver(), flaky), (f, t) -> pairs);

    int exit = runner.run(null, null, FROM, TO);

    assertThat(exit).isEqualTo(CompletenessRunner.EXIT_DIVERGED);
    assertThat(verified)
        .as("every pair after the throwing one must still have been read")
        .contains("prod_real/copytrade-v1", "staging_paper/watchlist-trigger-v1");
  }

  // A weekend / holiday / idle day must not break the streak.
  @Test
  void anEmptyWindowPasses() {
    assertThat(runner(List.of()).run(null, null, FROM, TO)).isEqualTo(CompletenessRunner.EXIT_PASS);
  }

  // The single-pair CLI contract still works for ad-hoc investigation, and must NOT consult the
  // discovery source (passing a pair that discovery would not have returned still runs).
  @Test
  void explicitPairIsVerifiedWithoutDiscovery() {
    givePair("prod_real", "copytrade-v1", "corr-x", false); // unclosed, nothing open -> divergence
    AuditPairSource exploding =
        (from, to) -> {
          throw new AssertionError("discovery must not be consulted when a pair is given");
        };
    AuditEventSource source =
        (tenantId, strategyId, from, to) ->
            eventsByPair.getOrDefault(tenantId + "/" + strategyId, List.of());
    CompletenessRunner runner =
        new CompletenessRunner(
            new AuditCompletenessVerifier(source, new LedgerRederiver(), (t, s) -> Set.of()),
            exploding);

    assertThat(runner.run("prod_real", "copytrade-v1", FROM, TO))
        .isEqualTo(CompletenessRunner.EXIT_DIVERGED);
  }

  // #854 review: the n/a branch existed only to avoid an operator-facing contradiction, and nothing
  // asserted on it — exactly the kind of formatting a refactor regresses silently.
  @Test
  void scoreReadsNaWhenNothingSettledAndAPercentageOtherwise() {
    assertThat(CompletenessRunner.formatScore(report(0, 100.0)))
        .as("nothing settled: a ratio over zero must not print as a vacuous 100%")
        .isEqualTo("n/a");
    assertThat(CompletenessRunner.formatScore(report(1, 100.0))).isEqualTo("100.00%");
    assertThat(CompletenessRunner.formatScore(report(2, 50.0))).isEqualTo("50.00%");
  }

  private static AuditCompletenessVerifier.Report report(int settled, double score) {
    return new AuditCompletenessVerifier.Report(
        "prod_real",
        "copytrade-v1",
        OffsetDateTime.parse("2026-09-11T00:00:00Z"),
        OffsetDateTime.parse("2026-09-12T00:00:00Z"),
        0,
        settled,
        settled,
        0,
        score,
        List.of());
  }

  // Half a pair is a usage error, not a silent fall-through to verifying everything.
  @Test
  void tenantWithoutStrategyIsRejected() {
    AuditCompletenessApplication app = new AuditCompletenessApplication();
    assertThatThrownBy(
            () ->
                app.run(
                    new org.springframework.boot.DefaultApplicationArguments(
                        "--tenant=prod_real", "--from=2026-09-11", "--to=2026-09-12")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be given together");
  }
}
