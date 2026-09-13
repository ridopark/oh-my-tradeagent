package com.ohmytradeagent.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ohmytradeagent.contract.AuditEvent;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * #853: the window model. "Every entry has a terminal close" was asserted per-window, which is
 * unsound for a strategy that deliberately holds positions overnight — every multi-day hold
 * straddles the window boundary and scored as a fault. Measured on prod_real before the fix: a
 * window with two real entries scored 0.00% with two {@code MISSING_TERMINAL_CLOSE} divergences,
 * both of which were simply positions still open.
 *
 * <p>These are UNIT tests on purpose. The same cases exist in {@code AuditCompletenessVerifierIT},
 * but that is gated on {@code RUN_DB_ITS=true} plus Docker and therefore skips in CI and on a dev
 * box — and a skip is never a pass. The window logic needs no Postgres, only an {@link
 * AuditEventSource}, which that interface's own javadoc anticipates ("or an in-memory fixture (unit
 * tests)"). So the load-bearing assertions live here, where they always run.
 */
class AuditCompletenessVerifierTest {

  private static final String TENANT = "prod_real";
  private static final String STRATEGY = "copytrade-v1";

  private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-09-09T00:00:00Z");
  private static final OffsetDateTime TO = OffsetDateTime.parse("2026-09-10T00:00:00Z");

  /** Feeds the verifier a fixed event list; the window arguments are not re-filtered. */
  private static AuditEventSource source(List<AuditEvent> events) {
    return (tenantId, strategyId, from, to) -> events;
  }

  private static OpenPositionSource open(String... correlationIds) {
    return (tenantId, strategyId) -> Set.of(correlationIds);
  }

  private static AuditCompletenessVerifier verifier(
      List<AuditEvent> events, OpenPositionSource openPositions) {
    return new AuditCompletenessVerifier(source(events), new LedgerRederiver(), openPositions);
  }

  private static AuditEvent event(String correlationId, String kind, int minute) {
    AuditEvent e = new AuditEvent();
    e.setSchemaVersion(1L);
    e.setTenantId(TENANT);
    e.setStrategyId(STRATEGY);
    e.setEventId(UUID.randomUUID().toString());
    e.setOccurredAt(FROM.plusMinutes(minute));
    e.setKind(kind);
    e.setSubject(Map.of());
    e.setCorrelationId(correlationId);
    return e;
  }

  private static List<AuditEvent> enteredNotClosed(String corr) {
    return new ArrayList<>(
        List.of(
            event(corr, "SignalReceived", 0),
            event(corr, "SignalAccepted", 1),
            event(corr, "OrderSubmitted", 2),
            event(corr, "EntryFilled", 3),
            event(corr, "PositionEntered", 4)));
  }

  // THE regression this issue is about: the overnight hold. Entry in the window, no close, because
  // the position is still open. Must carry forward, not fault.
  @Test
  void unclosedLifecycleWhosePositionIsStillOpenIsCarriedForward() {
    String corr = "chat-messages-769797179992571914-1547246647782940833:0";

    AuditCompletenessVerifier.Report report =
        verifier(enteredNotClosed(corr), open(corr)).verify(TENANT, STRATEGY, FROM, TO);

    assertThat(report.divergences()).isEmpty();
    assertThat(report.openLifecycles()).isEqualTo(1);
    assertThat(report.totalLifecycles()).as("settled lifecycles only").isZero();
    assertThat(report.score()).isEqualTo(100.0);
    assertThat(report.passed()).isTrue();
  }

  // The teeth, preserved. #90 acceptance criterion 2 (a suppressed terminal close must fail) now
  // fires on exactly the case that IS a fault: the lifecycle never closed AND no position is open,
  // so the close event is genuinely missing rather than merely pending.
  @Test
  void unclosedLifecycleWithNoOpenPositionIsADivergence() {
    String corr = "signal-lost-close";

    AuditCompletenessVerifier.Report report =
        verifier(enteredNotClosed(corr), open()).verify(TENANT, STRATEGY, FROM, TO);

    assertThat(report.divergences()).hasSize(1);
    Divergence d = report.divergences().get(0);
    assertThat(d.kind()).isEqualTo(Divergence.Kind.MISSING_TERMINAL_CLOSE);
    assertThat(d.correlationId()).isEqualTo(corr);
    assertThat(d.detail()).contains("position_not_open");
    assertThat(report.openLifecycles()).isZero();
    assertThat(report.totalLifecycles()).isEqualTo(1);
    assertThat(report.score()).isEqualTo(0.0);
    assertThat(report.passed()).isFalse();
  }

  // Both at once, so the open lifecycle cannot be masking the faulty one or vice versa: the
  // denominator must count only the settled lifecycle.
  @Test
  void openAndLostLifecyclesAreScoredSeparately() {
    String stillOpen = "signal-open";
    String lostClose = "signal-lost";
    List<AuditEvent> events = enteredNotClosed(stillOpen);
    events.addAll(enteredNotClosed(lostClose));

    AuditCompletenessVerifier.Report report =
        verifier(events, open(stillOpen)).verify(TENANT, STRATEGY, FROM, TO);

    assertThat(report.divergences()).hasSize(1);
    assertThat(report.divergences().get(0).correlationId()).isEqualTo(lostClose);
    assertThat(report.openLifecycles()).isEqualTo(1);
    assertThat(report.totalLifecycles()).isEqualTo(1);
    assertThat(report.score()).isEqualTo(0.0);
  }

  // A normal intraday round trip is unaffected — it settles in the window and still scores.
  @Test
  void closedLifecycleStillScoresComplete() {
    String corr = "signal-round-trip";
    List<AuditEvent> events = enteredNotClosed(corr);
    events.add(event(corr, "PositionClosed", 9));

    AuditCompletenessVerifier.Report report =
        verifier(events, open()).verify(TENANT, STRATEGY, FROM, TO);

    assertThat(report.divergences()).isEmpty();
    assertThat(report.openLifecycles()).isZero();
    assertThat(report.totalLifecycles()).isEqualTo(1);
    assertThat(report.completeLifecycles()).isEqualTo(1);
    assertThat(report.passed()).isTrue();
  }

  // Fail CLOSED. If the open-position lookup is unavailable the verifier must refuse to score
  // rather
  // than treat "nothing known to be open" as "nothing is open" — the latter would fabricate a
  // MISSING_TERMINAL_CLOSE for every held position and blame the ledger for a Temporal outage.
  @Test
  void openPositionLookupFailureRefusesToScore() {
    OpenPositionSource broken =
        (tenantId, strategyId) -> {
          throw new IllegalStateException("Temporal visibility unavailable");
        };

    AuditCompletenessVerifier verifier = verifier(enteredNotClosed("signal-x"), broken);

    assertThatThrownBy(() -> verifier.verify(TENANT, STRATEGY, FROM, TO))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Temporal visibility unavailable");
  }
}
