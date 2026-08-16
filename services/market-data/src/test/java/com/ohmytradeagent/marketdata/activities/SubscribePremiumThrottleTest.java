package com.ohmytradeagent.marketdata.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.marketdata.activities.SubscribePremiumActivityImpl.ThrottleState;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The min-move throttle on the option-premium fan-out.
 *
 * <p>Why this exists at all: every signalled tick is a Temporal history event on a
 * PositionWorkflow, which is the only long-lived workflow here WITHOUT continue-as-new. At the
 * feed's fixed ~2s poll an armed position emits ~11,700 signals per RTH day — past this repo's own
 * 10,000-event watermark in under a trading day, on positions that live for days.
 *
 * <p>The dangerous failure is not "too many ticks", it is a throttle that suppresses the tick which
 * would have fired a stop. That is what {@link #slowMonotonicDeclineStillEmitsEveryStep()} pins.
 */
class SubscribePremiumThrottleTest {

  /** 1% — the shipped default. */
  private static SubscribePremiumActivityImpl activity(String pct) {
    return new SubscribePremiumActivityImpl(null, null, null, new BigDecimal(pct));
  }

  @Test
  void firstTickAlwaysEmits_seedingTheBaseline() {
    SubscribePremiumActivityImpl a = activity("0.01");
    assertThat(a.shouldEmit(new ThrottleState(), new BigDecimal("3.00"))).isTrue();
  }

  @Test
  void tickInsideTheBandIsSuppressed() {
    SubscribePremiumActivityImpl a = activity("0.01");
    ThrottleState t = new ThrottleState();
    a.shouldEmit(t, new BigDecimal("3.00")); // seed
    // 1% of 3.00 is 0.03, so 3.02 is a 0.02 move — inside the band.
    assertThat(a.shouldEmit(t, new BigDecimal("3.02"))).isFalse();
    // The suppressed tick must NOT move the baseline, or the band would creep with noise.
    assertThat(a.shouldEmit(t, new BigDecimal("3.03"))).isTrue();
  }

  @Test
  void rallyThenReverse_everyTickSurvivesTheThrottle() {
    // Same sequence as the feed guard's rallyThenReverse test, checked on the OTHER half of the
    // path. The guard and the throttle both sit between the poll and the workflow, so proving the
    // guard passes a tick proves nothing if the throttle then eats it. +25% and -10% are both far
    // past the 1% band, so the runner's exit tick reaches the workflow.
    SubscribePremiumActivityImpl a = activity("0.01");
    ThrottleState t = new ThrottleState();
    assertThat(a.shouldEmit(t, new BigDecimal("3.20"))).isTrue();
    assertThat(a.shouldEmit(t, new BigDecimal("4.00"))).isTrue();
    assertThat(a.shouldEmit(t, new BigDecimal("3.60"))).isTrue();
  }

  @Test
  void slowMonotonicDeclineStillEmitsEveryStep() {
    // THE safety property. A throttle keyed on per-tick delta would emit NOTHING here — every
    // individual step is tiny — and a position could bleed through its stop unobserved. Keying on
    // delta from the last EMITTED price means a drift accumulates and emits, bounding the trail's
    // blind spot to one step rather than losing the breach entirely.
    SubscribePremiumActivityImpl a = activity("0.01");
    ThrottleState t = new ThrottleState();
    a.shouldEmit(t, new BigDecimal("3.00")); // seed

    int emitted = 0;
    BigDecimal px = new BigDecimal("3.00");
    for (int i = 0; i < 100; i++) {
      px = px.subtract(new BigDecimal("0.005")); // half the band per tick
      if (a.shouldEmit(t, px)) {
        emitted++;
      }
    }
    // 100 ticks of 0.005 = a 0.50 decline. It must be observed repeatedly, not swallowed.
    assertThat(emitted).isGreaterThan(10);
    assertThat(px).isEqualByComparingTo("2.50");
  }

  @Test
  void aLargeCollapseEmitsImmediately() {
    // The case the trail exists for: a one-sided NBBO collapse must never be throttled away.
    SubscribePremiumActivityImpl a = activity("0.01");
    ThrottleState t = new ThrottleState();
    a.shouldEmit(t, new BigDecimal("4.00"));
    assertThat(a.shouldEmit(t, new BigDecimal("2.00"))).isTrue();
  }

  @Test
  void nonPositivePremiumNeverEmitsAndNeverSeeds() {
    // A zero/negative premium is not a price. Seeding the baseline from one would set the band from
    // garbage and could suppress every subsequent real tick.
    SubscribePremiumActivityImpl a = activity("0.01");
    ThrottleState t = new ThrottleState();
    assertThat(a.shouldEmit(t, BigDecimal.ZERO)).isFalse();
    assertThat(a.shouldEmit(t, new BigDecimal("-1.00"))).isFalse();
    assertThat(a.shouldEmit(t, null)).isFalse();
    // Baseline untouched, so the first REAL tick still seeds and emits.
    assertThat(a.shouldEmit(t, new BigDecimal("3.00"))).isTrue();
  }

  @Test
  void zeroDeltaPctEmitsEveryTick() {
    // The escape hatch: setting the property to 0 restores pre-throttle behaviour without a code
    // change, which is what the existing wiring tests rely on.
    SubscribePremiumActivityImpl a = activity("0");
    ThrottleState t = new ThrottleState();
    assertThat(a.shouldEmit(t, new BigDecimal("3.00"))).isTrue();
    assertThat(a.shouldEmit(t, new BigDecimal("3.00"))).isTrue();
  }
}
