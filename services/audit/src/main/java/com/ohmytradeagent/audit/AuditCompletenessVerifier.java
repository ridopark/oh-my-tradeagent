package com.ohmytradeagent.audit;

import com.ohmytradeagent.contract.AuditEvent;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Issue #90 verifier core. Pulls a window of audit events via {@link AuditEventSource}, runs {@link
 * LedgerRederiver}, and produces a {@link Report} with the divergences and a completeness score.
 *
 * <p>"Completeness score" (criterion 4) is defined as: of the unique position lifecycles in the
 * window (one per distinct {@code correlation_id} that has at least one ENTRY-kind event), what
 * fraction have zero divergences. 100% = every lifecycle is internally consistent; anything less is
 * a failure of the Phase 7 gate criterion (e). When the window contains zero lifecycles, the score
 * is reported as 100% (no events, no divergence) and the verifier exits with success — this matches
 * the documented "20 consecutive green days" semantics: a market-closed day with zero activity
 * should not break the streak.
 */
@Component
public final class AuditCompletenessVerifier {

  private static final Logger log = LoggerFactory.getLogger(AuditCompletenessVerifier.class);

  private final AuditEventSource source;
  private final LedgerRederiver rederiver;

  public AuditCompletenessVerifier(AuditEventSource source) {
    this(source, new LedgerRederiver());
  }

  AuditCompletenessVerifier(AuditEventSource source, LedgerRederiver rederiver) {
    this.source = source;
    this.rederiver = rederiver;
  }

  public Report verify(
      String tenantId,
      String strategyId,
      OffsetDateTime fromInclusive,
      OffsetDateTime toExclusive) {
    List<AuditEvent> events = source.readWindow(tenantId, strategyId, fromInclusive, toExclusive);
    List<Divergence> divergences = rederiver.rederive(events);

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
    // Intersect: only lifecycles that actually opened count toward the denominator. Unknown-kind
    // findings on neutral events are still reported in the divergence list but do not push the
    // score below 100% if no lifecycle is affected — they're a registry-drift signal, not a
    // ledger-completeness signal.
    Set<String> divergentLifecycles = new HashSet<>(entryCorrelations);
    divergentLifecycles.retainAll(divergentCorrelations);

    int totalLifecycles = entryCorrelations.size();
    int completeLifecycles = totalLifecycles - divergentLifecycles.size();
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
            score,
            divergences);
    log.info(
        "audit-completeness tenant={} strategy={} window=[{},{}) events={} lifecycles={} complete={} score={}",
        tenantId,
        strategyId,
        fromInclusive,
        toExclusive,
        events.size(),
        totalLifecycles,
        completeLifecycles,
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
      double score,
      List<Divergence> divergences) {

    public boolean passed() {
      return score == 100.0 && divergences.isEmpty();
    }
  }
}
