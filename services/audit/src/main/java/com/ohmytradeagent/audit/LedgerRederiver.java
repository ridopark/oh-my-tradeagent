package com.ohmytradeagent.audit;

import com.ohmytradeagent.contract.AuditEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, in-memory re-derivation of the position ledger from a sequence of {@link AuditEvent} rows.
 * Issue #90 acceptance criterion 1 requires that running the verifier on a clean tenant with N
 * synthetic BTO→fill→close cycles yields zero divergence; criterion 2 requires that suppressing one
 * event yields a divergence that names the missing event.
 *
 * <p>Algorithm (intentionally simple):
 *
 * <ol>
 *   <li>Group every event by {@code correlation_id}. The {@code signal_id} that ties {@code
 *       SignalReceived → EntryFilled → ExitRequested → PartialExitFilled → PositionClosed} is in
 *       this field on every event the workflows emit. Events with a null correlation_id are skipped
 *       — they are not part of a position lifecycle (e.g. KillSwitchTripped).
 *   <li>For each correlation group, scan ordered by {@code occurred_at} ascending and classify each
 *       event into one of: ENTRY, PARTIAL_EXIT_REQUEST, PARTIAL_EXIT_FILL, TERMINAL_CLOSE, or
 *       "neutral" (ignored for ledger purposes).
 *   <li>A lifecycle is well-formed if (a) it contains at least one ENTRY, (b) every
 *       PARTIAL_EXIT_REQUEST is followed by at least one PARTIAL_EXIT_FILL, and (c) the lifecycle
 *       ends with a TERMINAL_CLOSE event.
 *   <li>Any event whose {@code kind} is not in {@link AuditEventKinds#ALL_KINDS} is reported as
 *       {@code UNKNOWN_KIND} — this catches the drift case where the orchestrator emits a kind this
 *       build's registry doesn't know about.
 * </ol>
 *
 * <p>The verifier does not consult Postgres position state directly because there is no {@code
 * positions} table — positions live in Temporal workflows. See {@code docs/ops/} (the homelab
 * position-state derivation note) for the architectural background. The audit log is the
 * authoritative source of truth for state-changing events; if it is internally consistent then by
 * construction the re-derived ledger matches what the system did.
 */
public final class LedgerRederiver {

  /**
   * Re-derive the position ledger from {@code events} and return any divergence findings.
   *
   * <p>{@code events} need not be pre-sorted; the algorithm sorts each correlation group by {@code
   * occurred_at} internally. Inputs with the same {@code occurred_at} retain their input order
   * (stable sort).
   *
   * @param events the slice of {@code audit_log} rows for one (tenant, strategy, date range)
   * @return zero-or-more divergence findings, empty list when the ledger is complete
   */
  public List<Divergence> rederive(List<AuditEvent> events) {
    List<Divergence> divergences = new ArrayList<>();
    Map<String, List<AuditEvent>> byCorrelation = new HashMap<>();
    for (AuditEvent ev : events) {
      if (ev.getKind() == null) {
        continue;
      }
      if (!AuditEventKinds.ALL_KINDS.contains(ev.getKind())) {
        divergences.add(
            new Divergence(
                Divergence.Kind.UNKNOWN_KIND,
                ev.getCorrelationId() == null ? "<no-correlation>" : ev.getCorrelationId(),
                "kind=" + ev.getKind() + " event_id=" + ev.getEventId()));
        // Continue — an unknown kind doesn't block the rest of the scan.
      }
      String corr = ev.getCorrelationId();
      if (corr == null) {
        // Neutral events without a correlation_id are not part of a lifecycle (e.g.
        // KillSwitchTripped fires on the kill-switch workflow, not a signal).
        continue;
      }
      byCorrelation.computeIfAbsent(corr, k -> new ArrayList<>()).add(ev);
    }
    for (Map.Entry<String, List<AuditEvent>> entry : byCorrelation.entrySet()) {
      checkLifecycle(entry.getKey(), entry.getValue(), divergences);
    }
    return divergences;
  }

  private static void checkLifecycle(
      String correlationId, List<AuditEvent> events, List<Divergence> findings) {
    events.sort(
        Comparator.comparing(
            AuditEvent::getOccurredAt,
            Comparator.nullsFirst(Comparator.comparing(java.time.OffsetDateTime::toInstant))));

    boolean opened = false;
    boolean hardClosed = false;
    boolean anyClosed = false;
    String firstHardCloseEventId = null;
    int pendingExitRequests = 0;
    int exitFills = 0;

    for (AuditEvent ev : events) {
      String kind = ev.getKind();
      if (AuditEventKinds.ENTRY_KINDS.contains(kind)) {
        opened = true;
      } else if (AuditEventKinds.PARTIAL_EXIT_REQUEST_KINDS.contains(kind)) {
        pendingExitRequests++;
      } else if (AuditEventKinds.PARTIAL_EXIT_FILL_KINDS.contains(kind)) {
        exitFills++;
      } else if (AuditEventKinds.HARD_TERMINAL_CLOSE_KINDS.contains(kind)) {
        if (!hardClosed) {
          firstHardCloseEventId = ev.getEventId();
        }
        hardClosed = true;
        anyClosed = true;
      } else if (AuditEventKinds.SOFT_TERMINAL_CLOSE_KINDS.contains(kind)) {
        anyClosed = true;
      }
    }

    if (hardClosed && !opened) {
      findings.add(
          new Divergence(
              Divergence.Kind.ORPHAN_CLOSE_WITHOUT_ENTRY,
              correlationId,
              "close_event_id=" + firstHardCloseEventId));
    }
    if (opened && !anyClosed) {
      findings.add(
          new Divergence(
              Divergence.Kind.MISSING_TERMINAL_CLOSE,
              correlationId,
              "entry_present=true lifecycle_unclosed"));
    }
    if (pendingExitRequests > exitFills) {
      findings.add(
          new Divergence(
              Divergence.Kind.MISSING_PARTIAL_EXIT_FILL,
              correlationId,
              "exit_requests=" + pendingExitRequests + " exit_fills=" + exitFills));
    }
  }
}
