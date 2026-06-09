package com.ohmytradeagent.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.contract.AuditEvent;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Issue #90 acceptance criteria 1 and 2 verified against the pure in-memory re-deriver. These tests
 * do not touch Postgres; the Testcontainers IT covers the end-to-end DB-backed verifier.
 */
class LedgerRederiverTest {

  private final LedgerRederiver rederiver = new LedgerRederiver();

  @Test
  void cleanBtoFillCloseCycleYieldsZeroDivergence() {
    // Acceptance criterion 1 verbatim: "Running the verifier on a clean test tenant with N
    // synthetic BTO->fill->close cycles yields zero divergence."
    List<AuditEvent> events = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      String corr = "signal-" + i;
      events.add(event(corr, "SignalReceived", t(i, 0)));
      events.add(event(corr, "SignalAccepted", t(i, 1)));
      events.add(event(corr, "OrderSubmitted", t(i, 2)));
      events.add(event(corr, "EntryFilled", t(i, 3)));
      events.add(event(corr, "PositionEntered", t(i, 4)));
      events.add(event(corr, "ExitRequested", t(i, 5)));
      events.add(event(corr, "PartialExitRequested", t(i, 6)));
      events.add(event(corr, "PartialExitFilled", t(i, 7)));
      events.add(event(corr, "PositionClosed", t(i, 8)));
    }

    List<Divergence> findings = rederiver.rederive(events);

    assertThat(findings).as("clean BTO->fill->close cycles must yield zero divergence").isEmpty();
  }

  @Test
  void suppressingTerminalCloseProducesMissingTerminalCloseDivergence() {
    // Acceptance criterion 2 (first half): "Deliberately suppressing one audit event in a test
    // causes the verifier to exit non-zero with a diff identifying the missing event." The
    // structured detail names the affected correlation_id.
    String corr = "signal-42";
    List<AuditEvent> events =
        new ArrayList<>(
            List.of(
                event(corr, "SignalReceived", t(0, 0)),
                event(corr, "EntryFilled", t(0, 1)),
                event(corr, "PositionEntered", t(0, 2))
                // PositionClosed deliberately omitted.
                ));

    List<Divergence> findings = rederiver.rederive(events);

    assertThat(findings).hasSize(1);
    Divergence d = findings.get(0);
    assertThat(d.kind()).isEqualTo(Divergence.Kind.MISSING_TERMINAL_CLOSE);
    assertThat(d.correlationId()).isEqualTo(corr);
    assertThat(d.detail()).contains("lifecycle_unclosed");
  }

  @Test
  void suppressingPartialExitFillProducesMissingFillDivergence() {
    // Acceptance criterion 2 (second half): the missing-event diff identifies *which* kind is
    // missing — here, the fill that should follow the exit request.
    String corr = "signal-7";
    List<AuditEvent> events =
        new ArrayList<>(
            List.of(
                event(corr, "EntryFilled", t(0, 0)),
                event(corr, "PositionEntered", t(0, 1)),
                event(corr, "PartialExitRequested", t(0, 2)),
                // PartialExitFilled deliberately omitted.
                event(corr, "PositionClosed", t(0, 3))));

    List<Divergence> findings = rederiver.rederive(events);

    assertThat(findings).hasSize(1);
    Divergence d = findings.get(0);
    assertThat(d.kind()).isEqualTo(Divergence.Kind.MISSING_PARTIAL_EXIT_FILL);
    assertThat(d.correlationId()).isEqualTo(corr);
    assertThat(d.detail()).contains("exit_requests=1").contains("exit_fills=0");
  }

  @Test
  void entryExpiredWithoutEntryFilledIsNotADivergence() {
    // EntryExpired terminates the lifecycle on its own (the entry leg never filled).
    String corr = "signal-expired";
    List<AuditEvent> events =
        new ArrayList<>(
            List.of(
                event(corr, "SignalReceived", t(0, 0)),
                event(corr, "OrderSubmitted", t(0, 1)),
                event(corr, "EntryExpired", t(0, 2))));

    assertThat(rederiver.rederive(events)).isEmpty();
  }

  @Test
  void signalRejectedAloneIsNotADivergence() {
    // SignalRejected terminates without entry; legitimately complete.
    String corr = "signal-rejected";
    List<AuditEvent> events =
        new ArrayList<>(
            List.of(
                event(corr, "SignalReceived", t(0, 0)), event(corr, "SignalRejected", t(0, 1))));

    assertThat(rederiver.rederive(events)).isEmpty();
  }

  @Test
  void positionClosedWithoutPriorEntryReportsOrphanClose() {
    // Hard-terminal close with no prior entry on the same correlation_id is an actual divergence.
    String corr = "signal-orphan";
    List<AuditEvent> events = new ArrayList<>(List.of(event(corr, "PositionClosed", t(0, 0))));

    List<Divergence> findings = rederiver.rederive(events);
    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).kind()).isEqualTo(Divergence.Kind.ORPHAN_CLOSE_WITHOUT_ENTRY);
  }

  @Test
  void unknownKindIsReportedButDoesNotPoisonRest() {
    // Drift case: the orchestrator emits a kind this build doesn't recognize. The verifier
    // surfaces it as UNKNOWN_KIND so the registry gap is visible at runtime, but does not
    // misclassify the surrounding lifecycle.
    String corr = "signal-with-drift";
    List<AuditEvent> events =
        new ArrayList<>(
            List.of(
                event(corr, "EntryFilled", t(0, 0)),
                event(corr, "PositionEntered", t(0, 1)),
                event(corr, "SomeNewKindNotInRegistry", t(0, 2)),
                event(corr, "PositionClosed", t(0, 3))));

    List<Divergence> findings = rederiver.rederive(events);

    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).kind()).isEqualTo(Divergence.Kind.UNKNOWN_KIND);
    assertThat(findings.get(0).detail()).contains("SomeNewKindNotInRegistry");
  }

  @Test
  void flattenOriginPartialExitFilledWithoutRequestIsTolerated() {
    // Plan-2A R-AA-6: the scheduled-flatten fill now rides a PartialExitFilled (so it enters
    // realized P&L) WITHOUT a preceding PartialExitRequested (the flatten path never emits a
    // request). The verifier must tolerate this — a fill with no matching request must NOT raise
    // MissingPartialExitFill (only the reverse, a request with no fill, is a divergence) and must
    // NOT cause a double-terminal/orphan regression. Lifecycle: entry -> flatten fill -> terminal.
    String corr = "signal-flatten";
    List<AuditEvent> events =
        new ArrayList<>(
            List.of(
                event(corr, "SignalReceived", t(0, 0)),
                event(corr, "EntryFilled", t(0, 1)),
                event(corr, "PositionEntered", t(0, 2)),
                // Flatten fill: PartialExitFilled with NO preceding PartialExitRequested.
                event(corr, "PartialExitFilled", t(0, 3)),
                // Lifecycle markers emitted by the flatten path.
                event(corr, "EodForceFlattened", t(0, 4)),
                event(corr, "PositionClosed", t(0, 5))));

    List<Divergence> findings = rederiver.rederive(events);

    assertThat(findings)
        .as("a flatten-origin PartialExitFilled without a PartialExitRequested is tolerated")
        .isEmpty();
  }

  @Test
  void eventsWithoutCorrelationIdAreIgnoredForLifecycleCheck() {
    // KillSwitchTripped and similar workflow-scoped events have no correlation_id; they must
    // not raise spurious divergences.
    AuditEvent killSwitch = event(null, "KillSwitchTripped", t(0, 0));
    assertThat(rederiver.rederive(List.of(killSwitch))).isEmpty();
  }

  // ---- helpers ----

  private static AuditEvent event(String correlationId, String kind, OffsetDateTime occurredAt) {
    AuditEvent e = new AuditEvent();
    e.setSchemaVersion(1L);
    e.setTenantId("dev");
    e.setStrategyId("copytrade-v1");
    e.setEventId(java.util.UUID.randomUUID().toString());
    e.setOccurredAt(occurredAt);
    e.setKind(kind);
    e.setSubject(Map.of());
    e.setCorrelationId(correlationId);
    return e;
  }

  private static OffsetDateTime t(int signalIndex, int orderInSignal) {
    // Spread signal indices across days so sort order is stable across them.
    return OffsetDateTime.of(2026, 5, 1, 14, signalIndex, orderInSignal, 0, ZoneOffset.UTC);
  }
}
