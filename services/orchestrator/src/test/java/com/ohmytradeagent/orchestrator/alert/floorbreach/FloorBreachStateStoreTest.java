package com.ohmytradeagent.orchestrator.alert.floorbreach;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.orchestrator.alert.floorbreach.FloorBreachEvaluator.Evaluation;
import com.ohmytradeagent.orchestrator.alert.floorbreach.FloorBreachEvaluator.Status;
import com.ohmytradeagent.orchestrator.alert.floorbreach.FloorBreachStateStore.Decision;
import com.ohmytradeagent.orchestrator.alert.floorbreach.FloorBreachStateStore.PriorAlert;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Issue #779 T2: the hysteresis contract. One alert per confirmed breach; re-alert only per further
 * 10% step or after the interval; flicker around the line neither spams nor re-arms; UNKNOWN is
 * inert; the audit backstop suppresses a post-restart duplicate.
 */
class FloorBreachStateStoreTest {

  private static final String WF = "wf-1";
  private static final Duration INTERVAL = Duration.ofHours(4);
  private static final Instant T0 = Instant.parse("2026-08-21T14:00:00Z");

  private static Evaluation breach(int step) {
    return new Evaluation(Status.BREACH, BigDecimal.valueOf(step, 2), step, false);
  }

  private static Evaluation okInsideBand() {
    return new Evaluation(Status.OK, null, 0, false);
  }

  private static Evaluation okRecovered() {
    return new Evaluation(Status.OK, null, 0, true);
  }

  private static Evaluation unknown() {
    return new Evaluation(Status.UNKNOWN, null, 0, false);
  }

  private static FloorBreachStateStore store() {
    return new FloorBreachStateStore(wf -> null, INTERVAL);
  }

  private static Instant tick(int n) {
    return T0.plusSeconds(60L * n);
  }

  @Test
  void singleConfirmedBreach_alertsExactlyOnce() {
    FloorBreachStateStore s = store();
    assertThat(s.onTick(WF, breach(50), tick(0)).alert()).isFalse(); // 1st tick: unconfirmed
    Decision d = s.onTick(WF, breach(50), tick(1));
    assertThat(d.alert()).isTrue(); // 2nd consecutive tick: page
    assertThat(d.step()).isEqualTo(50);
    assertThat(s.onTick(WF, breach(50), tick(2)).alert()).isFalse(); // same step: silent
    assertThat(s.onTick(WF, breach(50), tick(3)).alert()).isFalse();
  }

  @Test
  void stepDeepening50to60_realerts() {
    FloorBreachStateStore s = store();
    s.onTick(WF, breach(50), tick(0));
    assertThat(s.onTick(WF, breach(50), tick(1)).alert()).isTrue();
    assertThat(s.onTick(WF, breach(55 / 10 * 10), tick(2)).alert()).isFalse(); // still step 50
    Decision d = s.onTick(WF, breach(60), tick(3));
    assertThat(d.alert()).isTrue();
    assertThat(d.step()).isEqualTo(60);
    assertThat(s.onTick(WF, breach(60), tick(4)).alert()).isFalse();
  }

  @Test
  void flickerAroundTheLine_recoveryBelowTheBand_alertsExactlyOnce() {
    FloorBreachStateStore s = store();
    int alerts = 0;
    // breach, breach (confirm → 1 alert), ok-inside-band, breach, breach, ok, breach, breach …
    Evaluation[] seq = {
      breach(50),
      breach(50),
      okInsideBand(),
      breach(50),
      breach(50),
      okInsideBand(),
      breach(50),
      breach(50)
    };
    for (int i = 0; i < seq.length; i++) {
      if (s.onTick(WF, seq[i], tick(i)).alert()) {
        alerts++;
      }
    }
    assertThat(alerts).isEqualTo(1);
  }

  @Test
  void recoveryAboveTheBand_thenRebreach_alertsASecondTime() {
    FloorBreachStateStore s = store();
    s.onTick(WF, breach(50), tick(0));
    assertThat(s.onTick(WF, breach(50), tick(1)).alert()).isTrue();
    // Genuine recovery: bid ≥ 1.10 x floor → full re-arm.
    s.onTick(WF, okRecovered(), tick(2));
    assertThat(s.onTick(WF, breach(50), tick(3)).alert()).isFalse(); // needs re-confirmation
    assertThat(s.onTick(WF, breach(50), tick(4)).alert()).isTrue(); // second page
  }

  @Test
  void unknownTicks_areInert_neitherConfirmNorRearmNorAlert() {
    FloorBreachStateStore s = store();
    s.onTick(WF, breach(50), tick(0));
    // UNKNOWN between the two breach ticks: does not reset the consecutive counter…
    assertThat(s.onTick(WF, unknown(), tick(1)).alert()).isFalse();
    // …so the next breach tick is the 2nd consecutive BREACH observation and pages.
    assertThat(s.onTick(WF, breach(50), tick(2)).alert()).isTrue();
    // UNKNOWN after the page does not re-arm either.
    s.onTick(WF, unknown(), tick(3));
    assertThat(s.onTick(WF, breach(50), tick(4)).alert()).isFalse();
  }

  @Test
  void intervalElapseWhileStillBreached_realertsOnce() {
    FloorBreachStateStore s = store();
    s.onTick(WF, breach(50), T0);
    assertThat(s.onTick(WF, breach(50), T0.plusSeconds(60)).alert()).isTrue();
    assertThat(s.onTick(WF, breach(50), T0.plus(Duration.ofHours(2))).alert()).isFalse();
    Decision d = s.onTick(WF, breach(50), T0.plus(Duration.ofHours(5)));
    assertThat(d.alert()).isTrue(); // interval elapsed, still breached: remind
    assertThat(s.onTick(WF, breach(50), T0.plus(Duration.ofHours(5)).plusSeconds(60)).alert())
        .isFalse();
  }

  @Test
  void auditBackstop_recentPriorRowAtSameStep_suppressesTheRestartDuplicate() {
    // Fresh empty store (a restarted pod) + a recent FloorBreachAlerted row at step 50 in
    // audit_log + current step 50 → the would-be first alert is suppressed and state rehydrated.
    FloorBreachStateStore s =
        new FloorBreachStateStore(
            wf -> new PriorAlert(50, T0.minus(Duration.ofMinutes(30))), INTERVAL);
    s.onTick(WF, breach(50), T0);
    assertThat(s.onTick(WF, breach(50), T0.plusSeconds(60)).alert()).isFalse();
    // But a DEEPER breach still pages (rehydration kept the deepest-step latch, not a mute).
    assertThat(s.onTick(WF, breach(60), T0.plusSeconds(120)).alert()).isTrue();
  }

  @Test
  void auditBackstop_stalePriorRow_doesNotSuppress() {
    FloorBreachStateStore s =
        new FloorBreachStateStore(
            wf -> new PriorAlert(50, T0.minus(Duration.ofHours(5))), INTERVAL);
    s.onTick(WF, breach(50), T0);
    assertThat(s.onTick(WF, breach(50), T0.plusSeconds(60)).alert()).isTrue();
  }

  @Test
  void backstopLookupThrow_isSwallowed_alertStillFires() {
    FloorBreachStateStore s =
        new FloorBreachStateStore(
            wf -> {
              throw new IllegalStateException("db down");
            },
            INTERVAL);
    s.onTick(WF, breach(50), T0);
    assertThat(s.onTick(WF, breach(50), T0.plusSeconds(60)).alert()).isTrue();
  }

  @Test
  void retainOnly_evictsClosedWorkflows_soAFutureBreachPagesAgain() {
    FloorBreachStateStore s = store();
    s.onTick(WF, breach(50), tick(0));
    assertThat(s.onTick(WF, breach(50), tick(1)).alert()).isTrue();
    s.retainOnly(Set.of("some-other-wf"));
    // State evicted: the workflow is gone; were it to reappear, confirmation starts over.
    assertThat(s.onTick(WF, breach(50), tick(2)).alert()).isFalse();
    assertThat(s.onTick(WF, breach(50), tick(3)).alert()).isTrue();
  }
}
