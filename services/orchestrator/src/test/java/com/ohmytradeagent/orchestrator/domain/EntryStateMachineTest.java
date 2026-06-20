package com.ohmytradeagent.orchestrator.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.domain.EntryStateMachine.Decision;
import com.ohmytradeagent.orchestrator.domain.EntryStateMachine.Direction;
import com.ohmytradeagent.orchestrator.domain.EntryStateMachine.State;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Exhaustive pure-unit coverage for {@link EntryStateMachine}: BREAKOUT and RETEST, each ABOVE and
 * BELOW, across fire / skip / no-cross / already-past / one-shot / continue-as-new-seed. No
 * Temporal.
 *
 * <p>T = 761.00, g = 0.005 => bandLow = 757.195, bandHigh = 764.805.
 */
class EntryStateMachineTest {

  private static final BigDecimal T = new BigDecimal("761.00");
  private static final BigDecimal G = new BigDecimal("0.005");

  private static EntryStateMachine breakout(Direction dir) {
    return new EntryStateMachine(StrategyConfig.EntryMode.BREAKOUT, dir, T, G);
  }

  private static EntryStateMachine retest(Direction dir) {
    return new EntryStateMachine(StrategyConfig.EntryMode.RETEST, dir, T, G);
  }

  private static BigDecimal p(String v) {
    return new BigDecimal(v);
  }

  // ---------- BREAKOUT / ABOVE ----------

  @Test
  void breakoutAbove_liveCrossIntoBand_fires() {
    EntryStateMachine m = breakout(Direction.ABOVE);
    assertThat(m.onTick(p("760.80"))).isEqualTo(Decision.NONE); // seeds prev, below T
    assertThat(m.onTick(p("761.40"))).isEqualTo(Decision.FIRE); // prev<T, in band
    assertThat(m.state()).isEqualTo(State.FIRED);
  }

  @Test
  void breakoutAbove_firstTickAlreadyPastBand_skips() {
    EntryStateMachine m = breakout(Direction.ABOVE);
    assertThat(m.onTick(p("760.50"))).isEqualTo(Decision.NONE); // seed
    assertThat(m.onTick(p("770.00"))).isEqualTo(Decision.SKIP); // ran past chase cap on cross
    assertThat(m.state()).isEqualTo(State.SKIPPED);
  }

  @Test
  void breakoutAbove_alreadyPastAtFirstObservedTick_neverFires() {
    // The leg is already above T at the very first observed tick: no live cross -> no fire.
    EntryStateMachine m = breakout(Direction.ABOVE);
    assertThat(m.onTick(p("762.00"))).isEqualTo(Decision.NONE); // seed only
    assertThat(m.onTick(p("763.00"))).isEqualTo(Decision.NONE); // prev already >= T, no cross
    assertThat(m.state()).isEqualTo(State.ARMED);
  }

  @Test
  void breakoutAbove_noCross_staysArmed() {
    EntryStateMachine m = breakout(Direction.ABOVE);
    assertThat(m.onTick(p("759.00"))).isEqualTo(Decision.NONE);
    assertThat(m.onTick(p("760.50"))).isEqualTo(Decision.NONE);
    assertThat(m.onTick(p("760.90"))).isEqualTo(Decision.NONE);
    assertThat(m.state()).isEqualTo(State.ARMED);
  }

  @Test
  void breakoutAbove_exactlyAtBandTop_fires() {
    EntryStateMachine m = breakout(Direction.ABOVE);
    m.onTick(p("760.00"));
    assertThat(m.onTick(p("764.805"))).isEqualTo(Decision.FIRE); // == bandHigh, inclusive
  }

  // ---------- BREAKOUT / BELOW ----------

  @Test
  void breakoutBelow_liveCrossIntoBand_fires() {
    EntryStateMachine m = breakout(Direction.BELOW);
    assertThat(m.onTick(p("761.50"))).isEqualTo(Decision.NONE); // seed, above T
    assertThat(m.onTick(p("760.60"))).isEqualTo(Decision.FIRE); // prev>T, T*(1-g)<=last<=T
    assertThat(m.state()).isEqualTo(State.FIRED);
  }

  @Test
  void breakoutBelow_firstTickAlreadyPastBand_skips() {
    EntryStateMachine m = breakout(Direction.BELOW);
    assertThat(m.onTick(p("761.20"))).isEqualTo(Decision.NONE); // seed
    assertThat(m.onTick(p("750.00"))).isEqualTo(Decision.SKIP); // below bandLow on cross
    assertThat(m.state()).isEqualTo(State.SKIPPED);
  }

  @Test
  void breakoutBelow_noCross_staysArmed() {
    EntryStateMachine m = breakout(Direction.BELOW);
    assertThat(m.onTick(p("763.00"))).isEqualTo(Decision.NONE);
    assertThat(m.onTick(p("761.50"))).isEqualTo(Decision.NONE);
    assertThat(m.state()).isEqualTo(State.ARMED);
  }

  // ---------- RETEST / ABOVE ----------

  @Test
  void retestAbove_breakoutThenPullback_fires() {
    EntryStateMachine m = retest(Direction.ABOVE);
    assertThat(m.onTick(p("765.50"))).isEqualTo(Decision.NONE); // > bandHigh -> BROKEN_OUT
    assertThat(m.state()).isEqualTo(State.BROKEN_OUT);
    assertThat(m.onTick(p("763.20"))).isEqualTo(Decision.FIRE); // pull-back into Z
    assertThat(m.state()).isEqualTo(State.FIRED);
  }

  @Test
  void retestAbove_breakoutThenFailed_skips() {
    EntryStateMachine m = retest(Direction.ABOVE);
    assertThat(m.onTick(p("765.50"))).isEqualTo(Decision.NONE); // BROKEN_OUT
    assertThat(m.onTick(p("756.00"))).isEqualTo(Decision.SKIP); // < bandLow -> support lost
    assertThat(m.state()).isEqualTo(State.SKIPPED);
  }

  @Test
  void retestAbove_neverClearsZone_staysArmed() {
    EntryStateMachine m = retest(Direction.ABOVE);
    // Never exceeds bandHigh, so no confirmed breakout, so no fire even while inside Z.
    assertThat(m.onTick(p("762.00"))).isEqualTo(Decision.NONE);
    assertThat(m.onTick(p("763.00"))).isEqualTo(Decision.NONE);
    assertThat(m.state()).isEqualTo(State.ARMED);
  }

  // ---------- RETEST / BELOW ----------

  @Test
  void retestBelow_breakdownThenPullback_fires() {
    EntryStateMachine m = retest(Direction.BELOW);
    assertThat(m.onTick(p("756.00"))).isEqualTo(Decision.NONE); // < bandLow -> BROKEN_OUT
    assertThat(m.state()).isEqualTo(State.BROKEN_OUT);
    assertThat(m.onTick(p("758.50"))).isEqualTo(Decision.FIRE); // pull-back up into Z
    assertThat(m.state()).isEqualTo(State.FIRED);
  }

  @Test
  void retestBelow_breakdownThenFailed_skips() {
    EntryStateMachine m = retest(Direction.BELOW);
    assertThat(m.onTick(p("756.00"))).isEqualTo(Decision.NONE); // BROKEN_OUT
    assertThat(m.onTick(p("766.00"))).isEqualTo(Decision.SKIP); // > bandHigh -> failed
    assertThat(m.state()).isEqualTo(State.SKIPPED);
  }

  // ---------- One-shot + stale + seed ----------

  @Test
  void oneShot_afterFire_subsequentTicksStayFire() {
    EntryStateMachine m = breakout(Direction.ABOVE);
    m.onTick(p("760.80"));
    assertThat(m.onTick(p("761.40"))).isEqualTo(Decision.FIRE);
    assertThat(m.onTick(p("762.00"))).isEqualTo(Decision.FIRE);
    assertThat(m.onTick(p("700.00"))).isEqualTo(Decision.FIRE);
  }

  @Test
  void oneShot_afterSkip_subsequentTicksStaySkip() {
    EntryStateMachine m = breakout(Direction.ABOVE);
    m.onTick(p("760.00"));
    assertThat(m.onTick(p("770.00"))).isEqualTo(Decision.SKIP);
    assertThat(m.onTick(p("761.40")))
        .isEqualTo(Decision.SKIP); // would have been in-band, but terminal
  }

  @Test
  void nullTick_isNoOpAndReturnsCurrentTerminal() {
    EntryStateMachine m = breakout(Direction.ABOVE);
    assertThat(m.onTick(null)).isEqualTo(Decision.NONE);
    m.onTick(p("760.80"));
    assertThat(m.onTick(null)).isEqualTo(Decision.NONE); // does not advance prev
    assertThat(m.onTick(p("761.40"))).isEqualTo(Decision.FIRE);
  }

  @Test
  void seed_restoresStateAndPrev_acrossContinueAsNew() {
    // Simulate continue-as-new: prev was 760.80 (below T), ARMED. Next live cross fires.
    EntryStateMachine m = breakout(Direction.ABOVE);
    m.seed(State.ARMED, p("760.80"));
    assertThat(m.state()).isEqualTo(State.ARMED);
    assertThat(m.prev()).isEqualByComparingTo("760.80");
    assertThat(m.onTick(p("761.40"))).isEqualTo(Decision.FIRE);
  }

  @Test
  void seed_brokenOut_retest_firesOnPullbackAfterRestore() {
    EntryStateMachine m = retest(Direction.ABOVE);
    m.seed(State.BROKEN_OUT, p("765.50"));
    assertThat(m.onTick(p("763.20"))).isEqualTo(Decision.FIRE);
  }

  @Test
  void seed_brokenOut_retest_firstPostResumeTickAboveBand_doesNotFireSpuriously() {
    // Finding 3: across continue-as-new, a resumed BROKEN_OUT leg must NOT fire on the first
    // post-resume tick unless it is a valid in-zone pull-back. A tick still above bandHigh keeps
    // waiting; only the subsequent pull-back into Z fires.
    EntryStateMachine m = retest(Direction.ABOVE);
    m.seed(State.BROKEN_OUT, p("765.50"));
    assertThat(m.onTick(p("766.00"))).isEqualTo(Decision.NONE); // > bandHigh -> no spurious fire
    assertThat(m.state()).isEqualTo(State.BROKEN_OUT);
    assertThat(m.onTick(p("763.20"))).isEqualTo(Decision.FIRE); // valid pull-back into Z
  }
}
