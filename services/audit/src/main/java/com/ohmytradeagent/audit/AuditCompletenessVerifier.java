package com.ohmytradeagent.audit;

import com.ohmytradeagent.contract.AuditEvent;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Issue #90 verifier core. Pulls a window of audit events via {@link AuditEventSource}, runs {@link
 * LedgerRederiver}, and produces a {@link Report} with the divergences and a completeness score.
 *
 * <p>"Completeness score" (criterion 4) is defined as: of the position lifecycles that SETTLED in
 * the window (one per distinct {@code correlation_id} with at least one ENTRY-kind event, minus
 * those whose position is still open), what fraction have zero divergences. 100% = every lifecycle
 * is internally consistent; anything less is a failure of the Phase 7 gate criterion (e). When the
 * window contains zero lifecycles, the score is reported as 100% (no events, no divergence) and the
 * verifier exits with success — this matches the documented "20 consecutive green days" semantics:
 * a market-closed day with zero activity should not break the streak.
 */
@Component
public final class AuditCompletenessVerifier {

  private static final Logger log = LoggerFactory.getLogger(AuditCompletenessVerifier.class);

  private final AuditEventSource source;
  private final LedgerRederiver rederiver;
  private final OpenPositionSource openPositions;

  /**
   * {@code @Autowired} is required here, not decorative. Two constructors are declared (the one
   * below injects a {@link LedgerRederiver} for tests), and with more than one candidate and none
   * annotated, Spring stops choosing: it falls back to a no-arg constructor, finds none, and aborts
   * context refresh with {@code NoSuchMethodException: <init>()}. This is the same defect as PR
   * #486 and PR #683; it went unnoticed here only because the audit-completeness CronJob has never
   * been applied to the cluster, so the application has not had to start. {@code
   * SpringComponentConstructorGuardTest} in this module now catches it.
   */
  @Autowired
  public AuditCompletenessVerifier(AuditEventSource source, OpenPositionSource openPositions) {
    this(source, new LedgerRederiver(), openPositions);
  }

  AuditCompletenessVerifier(
      AuditEventSource source, LedgerRederiver rederiver, OpenPositionSource openPositions) {
    this.source = source;
    this.rederiver = rederiver;
    this.openPositions = openPositions;
  }

  public Report verify(
      String tenantId,
      String strategyId,
      OffsetDateTime fromInclusive,
      OffsetDateTime toExclusive) {
    List<AuditEvent> events = source.readWindow(tenantId, strategyId, fromInclusive, toExclusive);
    LedgerRederiver.Rederivation rederivation = rederiver.rederive(events);
    List<Divergence> divergences = new ArrayList<>(rederivation.divergences());

    // An unclosed lifecycle is only a fault if the position is NOT still open. The two are
    // indistinguishable in the log — measured: a prod_real position entered 2026-09-09 and still
    // open three days later has five events, all on the entry day, and nothing since. So ask
    // Temporal, which is where positions actually live, instead of assuming the window contains a
    // whole lifecycle (#853).
    Set<String> openNow = openPositions.openCorrelationIds(tenantId, strategyId);
    Set<String> openLifecycles = new HashSet<>();
    for (String correlationId : rederivation.unclosedLifecycles()) {
      if (openNow.contains(correlationId)) {
        openLifecycles.add(correlationId);
      } else {
        divergences.add(
            new Divergence(
                Divergence.Kind.MISSING_TERMINAL_CLOSE,
                correlationId,
                "entry_present=true lifecycle_unclosed position_not_open"));
      }
    }

    Set<String> entryCorrelations = new HashSet<>();
    for (AuditEvent ev : events) {
      if (ev.getCorrelationId() != null && AuditEventKinds.ENTRY_KINDS.contains(ev.getKind())) {
        entryCorrelations.add(ev.getCorrelationId());
      }
    }
    Set<String> divergentCorrelations = new HashSet<>();
    for (Divergence d : divergences) {
      divergentCorrelations.add(d.correlationId());
    }
    // Only lifecycles that actually opened count toward the denominator. Unknown-kind findings on
    // neutral events are still reported in the divergence list but do not push the score below 100%
    // if no lifecycle is affected — they're a registry-drift signal, not a ledger-completeness one.
    // (The intersection itself is taken against `settled` below; an earlier `divergentLifecycles`
    // variable here was superseded by that and removed.)
    // Open lifecycles leave BOTH sides of the ratio: they are unfinished, not inconsistent, so they
    // can neither pass nor fail. The denominator is the lifecycles that actually SETTLED in the
    // window. A window where everything is still open therefore scores 100% over zero settled
    // lifecycles, which is the same documented rule as an empty window.
    Set<String> settled = new HashSet<>(entryCorrelations);
    settled.removeAll(openLifecycles);
    Set<String> divergentSettled = new HashSet<>(settled);
    divergentSettled.retainAll(divergentCorrelations);

    int totalLifecycles = settled.size();
    int completeLifecycles = totalLifecycles - divergentSettled.size();
    double score = totalLifecycles == 0 ? 100.0 : 100.0 * completeLifecycles / totalLifecycles;

    Report report =
        new Report(
            tenantId,
            strategyId,
            fromInclusive,
            toExclusive,
            events.size(),
            totalLifecycles,
            completeLifecycles,
            openLifecycles.size(),
            score,
            List.copyOf(divergences));
    log.info(
        "audit-completeness tenant={} strategy={} window=[{},{}) events={} settled_lifecycles={}"
            + " complete={} open_carried_forward={} score={}",
        tenantId,
        strategyId,
        fromInclusive,
        toExclusive,
        events.size(),
        totalLifecycles,
        completeLifecycles,
        openLifecycles.size(),
        String.format("%.2f%%", score));
    return report;
  }

  /**
   * Verifier output. {@code score} is in percent (0..100). A run is considered passing when {@code
   * score == 100.0} AND no UNKNOWN_KIND divergence is reported (a registry gap is a build issue,
   * but it still warrants exit-non-zero so ops sees the drift in the daily CronJob history).
   */
  public record Report(
      String tenantId,
      String strategyId,
      OffsetDateTime fromInclusive,
      OffsetDateTime toExclusive,
      int totalEvents,
      int totalLifecycles,
      int completeLifecycles,
      int openLifecycles,
      double score,
      List<Divergence> divergences) {

    public boolean passed() {
      return score == 100.0 && divergences.isEmpty();
    }
  }
}
