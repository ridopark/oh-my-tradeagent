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
import com.ohmytradeagent.exec.journal.OrderIntentJournal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MidWalkExecutorTest {

  private static final Instant START = Instant.parse("2026-10-05T18:00:00Z");
  private static final BigDecimal TICK = new BigDecimal("0.01");

  private final StubBroker broker = new StubBroker();
  private final OrderIntentJournal journal = mock(OrderIntentJournal.class);
  private Instant now = START;
  private int rungWaits = 0;
  private IntConsumer onRungWait = k -> {};
  private MidWalkExecutor executor;

  @BeforeEach
  void setUp() {
    when(journal.recordComboIntent(any())).thenReturn(true);
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
    executor = new MidWalkExecutor(journal, clock, sleeper);
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
        "paper",
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
    return "stub-" + ClientOrderId.forIntent("condor-2026-10-05-r" + step);
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

    MidWalkExecutor.Result r =
        executor.execute(broker, request(bd("0.44"), Duration.ofMinutes(10)));

    assertThat(r.outcome()).isEqualTo(Outcome.FILLED);
    assertThat(r.ladder())
        .extracting(MidWalkExecutor.Rung::limitCredit)
        .usingElementComparator(BigDecimal::compareTo)
        .containsExactly(bd("0.45"), bd("0.44"), bd("0.43"));
    assertThat(broker.placedMlegOrders()).hasSize(3);
    assertThat(broker.placedMlegOrders())
        .allSatisfy(p -> assertThat(p.clientOrderId()).startsWith("condor-"));
    assertThat(r.slippageVsMid()).isEqualByComparingTo("0.02");
    String filledKey = "condor-2026-10-05-r2";
    verify(journal).markFilled(eq(filledKey), eq(1L), any(), any(), eq("poll"));
    verify(journal).recordSlippageVsMid(eq(filledKey), argThat(v -> v.compareTo(bd("0.02")) == 0));
    verify(journal).markCancelledIfSubmitted("condor-2026-10-05-r0");
    verify(journal).markCancelledIfSubmitted("condor-2026-10-05-r1");
  }

  @Test
  void alreadyFilledDuringCancel_reconcilesFill_neverSendsAnotherRung() throws Exception {
    // Fill lands between the status read (OPEN) and the cancel: the cancel answers ALREADY_FILLED.
    broker.fillOnCancelForTest(
        boid(1), 1L, bd("-0.44"), OffsetDateTime.parse("2026-10-05T18:00:20Z"));

    MidWalkExecutor.Result r =
        executor.execute(broker, request(bd("0.44"), Duration.ofMinutes(10)));

    assertThat(r.outcome()).isEqualTo(Outcome.FILLED);
    assertThat(broker.placedMlegOrders()).hasSize(2);
    verify(journal)
        .markFilled(eq("condor-2026-10-05-r1"), eq(1L), any(), any(), eq("cancel_reconcile"));
    verify(journal, never()).markCancelledIfSubmitted("condor-2026-10-05-r1");
  }

  @Test
  void cancelFailed_haltsWithoutReplace_neverTreatedAsFlat() throws Exception {
    broker.failCancelForTest(boid(0), "alpaca status 422: order not cancelable");

    MidWalkExecutor.Result r =
        executor.execute(broker, request(bd("0.44"), Duration.ofMinutes(10)));

    assertThat(r.outcome()).isEqualTo(Outcome.HALTED);
    assertThat(broker.placedMlegOrders()).hasSize(1);
    verify(journal).markCancelFailed(eq("condor-2026-10-05-r0"), anyString());
    verify(journal, never()).markCancelledIfSubmitted(anyString());
  }

  @Test
  void partialFillOnCancel_stopsWalk_neverReSendsRemainder() throws Exception {
    onRungWait = i -> broker.setPartialFillForTest(boid(0), 1L, bd("-0.45"));

    MidWalkExecutor.Result r =
        executor.execute(
            broker,
            new MidWalkExecutor.WalkRequest(
                "staging_paper",
                "gated_condor",
                "sig",
                "paper",
                "2026-10-05",
                3L,
                legs(),
                bd("0.44"),
                TICK,
                START.plus(Duration.ofMinutes(10))));

    assertThat(r.outcome()).isEqualTo(Outcome.PARTIAL);
    assertThat(r.filledQty()).isEqualTo(1L);
    assertThat(broker.placedMlegOrders()).hasSize(1);
    verify(journal).markCancelledWithFill(eq("condor-2026-10-05-r0"), eq(1L), any(), any());
  }

  @Test
  void abandonBelowModelMinusTwoTicks_journalsFullLadder() throws Exception {
    // mid 0.45, model 0.45 → floor 0.43: rungs 0.45, 0.44, 0.43 tried, 0.42 abandons unsent.
    MidWalkExecutor.Result r =
        executor.execute(broker, request(bd("0.45"), Duration.ofMinutes(10)));

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
    MidWalkExecutor.Result r =
        executor.execute(broker, request(bd("0.30"), Duration.ofSeconds(25)));

    // 0s: rung0, 10s: rung1, 20s: rung2, 30s >= 25s deadline → abandon.
    assertThat(r.outcome()).isEqualTo(Outcome.ABANDONED);
    assertThat(r.reason()).contains("deadline");
    assertThat(broker.placedMlegOrders()).hasSize(3);
  }

  @Test
  void alreadyJournaledRung_haltsWithoutPlacing() throws Exception {
    // A re-run of the same attempt (activity retry) must never re-walk: the journal already holds
    // rung 0, so nothing is sent.
    when(journal.recordComboIntent(any())).thenReturn(false);

    MidWalkExecutor.Result r =
        executor.execute(broker, request(bd("0.44"), Duration.ofMinutes(10)));

    assertThat(r.outcome()).isEqualTo(Outcome.HALTED);
    assertThat(broker.placedMlegOrders()).isEmpty();
  }

  @Test
  void nonPaperBrokerTarget_isRejected() throws Exception {
    MidWalkExecutor.WalkRequest live =
        new MidWalkExecutor.WalkRequest(
            "t", "gated_condor", "sig", "live", "a", 1L, legs(), bd("0.44"), TICK, START);

    assertThatThrownBy(() -> executor.execute(broker, live))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(broker.placedMlegOrders()).isEmpty();
  }
}
