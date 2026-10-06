package com.ohmytradeagent.exec.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.exec.broker.MidWalkExecutor.Outcome;
import com.ohmytradeagent.exec.broker.stub.StubBroker;
import com.ohmytradeagent.exec.journal.ComboIntent;
import com.ohmytradeagent.exec.journal.JournaledOrder;
import com.ohmytradeagent.exec.journal.OrderIntentJournal;
import com.ohmytradeagent.exec.journal.OrderState;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MidWalkExecutorTest {

  private static final Instant START = Instant.parse("2026-10-05T18:00:00Z");
  private static final BigDecimal TICK = new BigDecimal("0.01");

  private final StubBroker broker = new StubBroker();
  private final OrderIntentJournal journal = mock(OrderIntentJournal.class);
  private final BrokerClientRegistry registry = mock(BrokerClientRegistry.class);
  private Instant now = START;
  private int rungWaits = 0;
  private IntConsumer onRungWait = k -> {};
  private MidWalkExecutor executor;

  @BeforeEach
  void setUp() {
    when(journal.recordComboIntent(any())).thenReturn(true);
    when(registry.brokerFor("staging_paper", "alpaca")).thenReturn(broker);
    Clock clock =
        new Clock() {
          @Override
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return now;
          }
        };
    MidWalkExecutor.Sleeper sleeper =
        d -> {
          now = now.plus(d);
          if (d.equals(MidWalkExecutor.RUNG_WAIT)) {
            onRungWait.accept(rungWaits++);
          }
        };
    executor = new MidWalkExecutor(journal, registry, clock, sleeper);
  }

  /**
   * Legs quoted so the net-credit mid is exactly 0.45: short put mid 1.00, long put mid 0.30, short
   * call mid 0.95, long call mid 1.20 → 1.00 − 0.30 + 0.95 − 1.20 = 0.45.
   */
  private static List<MidWalkExecutor.Leg> legs() {
    return List.of(
        new MidWalkExecutor.Leg("XSP   261005P00570000", "BUY", 1L, bd("0.25"), bd("0.35")),
        new MidWalkExecutor.Leg("XSP   261005P00573000", "SELL", 1L, bd("0.95"), bd("1.05")),
        new MidWalkExecutor.Leg("XSP   261005C00575000", "SELL", 1L, bd("0.90"), bd("1.00")),
        new MidWalkExecutor.Leg("XSP   261005C00578000", "BUY", 1L, bd("1.15"), bd("1.25")));
  }

  private static MidWalkExecutor.WalkRequest request(BigDecimal modelCredit, Duration window) {
    return new MidWalkExecutor.WalkRequest(
        "staging_paper",
        "gated_condor",
        "sig-2026-10-05",
        "alpaca-paper",
        "2026-10-05",
        1L,
        legs(),
        modelCredit,
        TICK,
        START.plus(window));
  }

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  private static String boid(int step) {
    return "stub-"
        + ClientOrderId.forIntent("condor-staging_paper-gated_condor-2026-10-05-r" + step);
  }

  private void fillAtRungWait(int k, String credit) {
    onRungWait =
        i -> {
          if (i == k) {
            broker.setAlreadyFilled(
                boid(k), 1L, bd(credit).negate(), OffsetDateTime.parse("2026-10-05T18:00:30Z"));
          }
        };
  }

  @Test
  void netMid_signsShortsPositiveAndLongsNegative() {
    assertThat(MidWalkExecutor.netMid(legs())).isEqualByComparingTo("0.45");
  }

  @Test
  void slippageVsMid_isMidMinusAchievedCredit() {
    // Positive = credit given up vs the submit-time mid; the credit is the ABS of Alpaca's signed
    // (negative-for-credit) mleg fill price.
    assertThat(MidWalkExecutor.slippageVsMid(bd("0.45"), bd("-0.43"))).isEqualByComparingTo("0.02");
    assertThat(MidWalkExecutor.slippageVsMid(bd("0.45"), bd("0.46"))).isEqualByComparingTo("-0.01");
  }

  @Test
  void ladder_fillsAtStepK_stepsDownOneTickPerRung() throws Exception {
    fillAtRungWait(2, "0.43");

    MidWalkExecutor.Result r = executor.execute(request(bd("0.44"), Duration.ofMinutes(10)));

    assertThat(r.outcome()).isEqualTo(Outcome.FILLED);
    assertThat(r.ladder())
        .extracting(MidWalkExecutor.Rung::limitCredit)
        .usingElementComparator(BigDecimal::compareTo)
        .containsExactly(bd("0.45"), bd("0.44"), bd("0.43"));
    assertThat(broker.placedMlegOrders()).hasSize(3);
    assertThat(broker.placedMlegOrders())
        .allSatisfy(p -> assertThat(p.clientOrderId()).startsWith("condor-"));
    assertThat(r.slippageVsMid()).isEqualByComparingTo("0.02");
    String filledKey = "condor-staging_paper-gated_condor-2026-10-05-r2";
    verify(journal).markFilled(eq(filledKey), eq(1L), any(), any(), eq("poll"));
    verify(journal).recordSlippageVsMid(eq(filledKey), argThat(v -> v.compareTo(bd("0.02")) == 0));
    verify(journal).markCancelledIfSubmitted("condor-staging_paper-gated_condor-2026-10-05-r0");
    verify(journal).markCancelledIfSubmitted("condor-staging_paper-gated_condor-2026-10-05-r1");
  }

  @Test
  void alreadyFilledDuringCancel_reconcilesFill_neverSendsAnotherRung() throws Exception {
    // Fill lands between the status read (OPEN) and the cancel: the cancel answers ALREADY_FILLED.
    broker.fillOnCancelForTest(
        boid(1), 1L, bd("-0.44"), OffsetDateTime.parse("2026-10-05T18:00:20Z"));

    MidWalkExecutor.Result r = executor.execute(request(bd("0.44"), Duration.ofMinutes(10)));

    assertThat(r.outcome()).isEqualTo(Outcome.FILLED);
    assertThat(broker.placedMlegOrders()).hasSize(2);
    verify(journal)
        .markFilled(
            eq("condor-staging_paper-gated_condor-2026-10-05-r1"),
            eq(1L),
            any(),
            any(),
            eq("cancel_reconcile"));
    verify(journal, never())
        .markCancelledIfSubmitted("condor-staging_paper-gated_condor-2026-10-05-r1");
  }

  @Test
  void cancelFailed_haltsWithoutReplace_neverTreatedAsFlat() throws Exception {
    broker.failCancelForTest(boid(0), "alpaca status 422: order not cancelable");

    MidWalkExecutor.Result r = executor.execute(request(bd("0.44"), Duration.ofMinutes(10)));

    assertThat(r.outcome()).isEqualTo(Outcome.HALTED);
    assertThat(broker.placedMlegOrders()).hasSize(1);
    verify(journal)
        .markCancelFailed(eq("condor-staging_paper-gated_condor-2026-10-05-r0"), anyString());
    verify(journal, never()).markCancelledIfSubmitted(anyString());
  }

  @Test
  void partialFillOnCancel_stopsWalk_neverReSendsRemainder() throws Exception {
    onRungWait = i -> broker.setPartialFillForTest(boid(0), 1L, bd("-0.45"));

    MidWalkExecutor.Result r =
        executor.execute(
            new MidWalkExecutor.WalkRequest(
                "staging_paper",
                "gated_condor",
                "sig",
                "alpaca-paper",
                "2026-10-05",
                3L,
                legs(),
                bd("0.44"),
                TICK,
                START.plus(Duration.ofMinutes(10))));

    assertThat(r.outcome()).isEqualTo(Outcome.PARTIAL);
    assertThat(r.filledQty()).isEqualTo(1L);
    assertThat(broker.placedMlegOrders()).hasSize(1);
    verify(journal)
        .markCancelledWithFill(
            eq("condor-staging_paper-gated_condor-2026-10-05-r0"), eq(1L), any(), any());
  }

  @Test
  void abandonBelowModelMinusTwoTicks_journalsFullLadder() throws Exception {
    // mid 0.45, model 0.45 → floor 0.43: rungs 0.45, 0.44, 0.43 tried, 0.42 abandons unsent.
    MidWalkExecutor.Result r = executor.execute(request(bd("0.45"), Duration.ofMinutes(10)));

    assertThat(r.outcome()).isEqualTo(Outcome.ABANDONED);
    assertThat(r.reason()).contains("below_floor");
    assertThat(broker.placedMlegOrders()).hasSize(3);
    ArgumentCaptor<ComboIntent> combos = ArgumentCaptor.forClass(ComboIntent.class);
    verify(journal, times(3)).recordComboIntent(combos.capture());
    assertThat(combos.getAllValues())
        .extracting(ComboIntent::netCredit)
        .usingElementComparator(BigDecimal::compareTo)
        .containsExactly(bd("0.45"), bd("0.44"), bd("0.43"));
    // Every rung row carries the per-leg NBBO captured at submit.
    assertThat(combos.getAllValues())
        .allSatisfy(
            c -> {
              assertThat(c.intentKey()).startsWith("condor-");
              assertThat(c.legs()).hasSize(4);
              assertThat(c.legs().get(1).nbboMid()).isEqualByComparingTo("1.00");
            });
    assertThat(r.ladder()).hasSize(3);
  }

  @Test
  void abandonAtDeadline_stopsBeforeNextRung() throws Exception {
    MidWalkExecutor.Result r = executor.execute(request(bd("0.30"), Duration.ofSeconds(25)));

    // 0s: rung0, 10s: rung1, 20s: rung2, 30s >= 25s deadline → abandon.
    assertThat(r.outcome()).isEqualTo(Outcome.ABANDONED);
    assertThat(r.reason()).contains("deadline");
    assertThat(broker.placedMlegOrders()).hasSize(3);
  }

  @Test
  void alreadyJournaledTerminalRung_haltsWithoutPlacing() throws Exception {
    // A re-run of the same attempt whose rung 0 already ended (CANCELLED) must never re-walk.
    when(journal.recordComboIntent(any())).thenReturn(false);
    when(journal.findByIntentKey(key(0)))
        .thenReturn(Optional.of(row(key(0), OrderState.CANCELLED, "stub-x", "0.45")));

    MidWalkExecutor.Result r = executor.execute(request(bd("0.44"), Duration.ofMinutes(10)));

    assertThat(r.outcome()).isEqualTo(Outcome.HALTED);
    assertThat(broker.placedMlegOrders()).isEmpty();
  }

  @Test
  void recordedRungWithoutBrokerId_isRePlacedWithSameCid_thenMarkedSubmitted() throws Exception {
    // #897 blocker 1: the prior attempt's placement threw ambiguously — the broker may or may not
    // hold the order. The re-run re-sends the SAME client_order_id (idempotent) and tracks it.
    when(journal.recordComboIntent(any())).thenReturn(false);
    when(journal.findByIntentKey(key(0)))
        .thenReturn(Optional.of(row(key(0), OrderState.RECORDED, null, "0.45")));
    fillAtRungWait(0, "0.45");

    MidWalkExecutor.Result r = executor.execute(request(bd("0.44"), Duration.ofMinutes(10)));

    assertThat(r.outcome()).isEqualTo(Outcome.FILLED);
    assertThat(broker.placedMlegOrders())
        .singleElement()
        .satisfies(
            p -> {
              assertThat(p.clientOrderId()).isEqualTo(ClientOrderId.forIntent(key(0)));
              assertThat(p.netCredit()).isEqualByComparingTo("0.45");
            });
    verify(journal).markSubmittedIfRecorded(key(0), boid(0));
  }

  @Test
  void recordedRungAlreadyAtBroker_resolvesByCid_neverPlacesASecondOrder() throws Exception {
    // The ambiguous failure DID reach the broker: the same-cid re-send resolves to that order.
    when(journal.recordComboIntent(any())).thenReturn(false);
    when(journal.findByIntentKey(key(0)))
        .thenReturn(Optional.of(row(key(0), OrderState.RECORDED, null, "0.45")));
    broker.placeMlegOrder(
        new PlaceMlegOrderRequest(
            "staging_paper",
            ClientOrderId.forIntent(key(0)),
            1L,
            bd("0.45"),
            List.of(
                new PlaceMlegOrderRequest.Leg("XSP   261005P00573000", "SELL", 1L),
                new PlaceMlegOrderRequest.Leg("XSP   261005P00570000", "BUY", 1L))));
    fillAtRungWait(0, "0.45");

    MidWalkExecutor.Result r = executor.execute(request(bd("0.44"), Duration.ofMinutes(10)));

    assertThat(r.outcome()).isEqualTo(Outcome.FILLED);
    assertThat(broker.placedMlegOrders()).hasSize(1); // only the original placement
    verify(journal).markSubmittedIfRecorded(key(0), boid(0));
  }

  @Test
  void submittedRung_isSettledWhereItStands_notReSent() throws Exception {
    when(journal.recordComboIntent(any())).thenReturn(false);
    when(journal.findByIntentKey(key(0)))
        .thenReturn(Optional.of(row(key(0), OrderState.SUBMITTED, boid(0), "0.45")));
    broker.setAlreadyFilled(boid(0), 1L, bd("-0.45"), OffsetDateTime.parse("2026-10-05T18:00:05Z"));

    MidWalkExecutor.Result r = executor.execute(request(bd("0.44"), Duration.ofMinutes(10)));

    assertThat(r.outcome()).isEqualTo(Outcome.FILLED);
    assertThat(broker.placedMlegOrders()).isEmpty();
    verify(journal).markFilled(eq(key(0)), eq(1L), any(), any(), eq("poll"));
  }

  @Test
  void thrownPlacement_marksPlaceFailed_andRethrows() {
    OptionsBroker failing = mock(OptionsBroker.class);
    when(registry.brokerFor("staging_paper", "alpaca")).thenReturn(failing);
    when(failing.placeMlegOrder(any())).thenThrow(new IllegalStateException("socket reset"));

    assertThatThrownBy(() -> executor.execute(request(bd("0.44"), Duration.ofMinutes(10))))
        .hasMessageContaining("socket reset");
    verify(journal).markPlaceFailed(key(0), "socket reset");
    verify(journal, never()).markSubmittedIfRecorded(anyString(), anyString());
  }

  @Test
  void modelCreditWithinTwoTicks_abandonsBeforeJournaling() throws Exception {
    MidWalkExecutor.Result r = executor.execute(request(bd("0.02"), Duration.ofMinutes(10)));

    assertThat(r.outcome()).isEqualTo(Outcome.ABANDONED);
    assertThat(r.reason()).contains("invalid_pricing");
    verify(journal, never()).recordComboIntent(any());
    verify(registry, never()).brokerFor(anyString(), anyString());
  }

  @Test
  void nonPositiveNetMid_abandonsBeforeJournaling() throws Exception {
    List<MidWalkExecutor.Leg> debit =
        List.of(
            new MidWalkExecutor.Leg("XSP   261005P00573000", "SELL", 1L, bd("0.10"), bd("0.12")),
            new MidWalkExecutor.Leg("XSP   261005P00570000", "BUY", 1L, bd("0.30"), bd("0.32")));
    MidWalkExecutor.Result r =
        executor.execute(
            new MidWalkExecutor.WalkRequest(
                "staging_paper",
                "gated_condor",
                "sig",
                "alpaca-paper",
                "2026-10-05",
                1L,
                debit,
                bd("0.44"),
                TICK,
                START.plus(Duration.ofMinutes(10))));

    assertThat(r.outcome()).isEqualTo(Outcome.ABANDONED);
    verify(journal, never()).recordComboIntent(any());
  }

  @Test
  void brokerIsResolvedFromBrokerTargetThroughRegistry() throws Exception {
    // #897 blocker 2: no caller-supplied broker instance exists — the only handle is the one the
    // registry returns for (tenant, provider-of-brokerTarget).
    fillAtRungWait(0, "0.45");

    executor.execute(request(bd("0.44"), Duration.ofMinutes(10)));

    verify(registry).brokerFor("staging_paper", "alpaca");
  }

  @Test
  void nonPaperBrokerTarget_isRejectedBeforeRegistryOrJournal() {
    for (String target : new String[] {"alpaca-live", "live", "paper", null}) {
      MidWalkExecutor.WalkRequest live =
          new MidWalkExecutor.WalkRequest(
              "t", "gated_condor", "sig", target, "a", 1L, legs(), bd("0.44"), TICK, START);

      assertThatThrownBy(() -> executor.execute(live)).isInstanceOf(IllegalArgumentException.class);
    }
    verify(registry, never()).brokerFor(anyString(), anyString());
    verify(journal, never()).recordComboIntent(any());
    assertThat(broker.placedMlegOrders()).isEmpty();
  }

  @Test
  void intentKeyEmbedsTenantAndStrategy_soSameDayAttemptsNeverCollideAcrossTenants() {
    MidWalkExecutor.WalkRequest a = request(bd("0.44"), Duration.ofMinutes(10));
    MidWalkExecutor.WalkRequest b =
        new MidWalkExecutor.WalkRequest(
            "other_tenant",
            "gated_condor",
            a.signalId(),
            a.brokerTarget(),
            a.attemptId(),
            1L,
            legs(),
            bd("0.44"),
            TICK,
            a.deadline());

    assertThat(MidWalkExecutor.rungIntentKey(a, 0))
        .isEqualTo("condor-staging_paper-gated_condor-2026-10-05-r0")
        .isNotEqualTo(MidWalkExecutor.rungIntentKey(b, 0));
  }

  private static String key(int step) {
    return "condor-staging_paper-gated_condor-2026-10-05-r" + step;
  }

  private static JournaledOrder row(
      String intentKey, OrderState state, String brokerOrderId, String limit) {
    return new JournaledOrder(
        intentKey,
        "sig",
        "staging_paper",
        "gated_condor",
        "alpaca-paper",
        ClientOrderId.forIntent(intentKey),
        "MLEG",
        "SELL",
        1L,
        bd(limit),
        state,
        brokerOrderId,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        0L);
  }
}
