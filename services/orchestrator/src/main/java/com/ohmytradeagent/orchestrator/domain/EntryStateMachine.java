package com.ohmytradeagent.orchestrator.domain;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.WatchlistTriggerPayload;
import java.math.BigDecimal;

/**
 * Pure, Temporal-free entry state machine for the watchlist-trigger strategy. Given an {@code
 * entry_mode}, a {@code direction} (ABOVE/call | BELOW/put), a trigger level {@code T}, and a gap
 * tolerance {@code g}, it consumes a stream of trade-price updates and decides when to FIRE, when
 * to SKIP (definitively give up), or to keep waiting (NONE).
 *
 * <p>Both modes require a <b>live cross</b>: a leg merely found already past {@code T} at the first
 * observed tick never fires — the first tick only seeds {@code prev}. Stale/halted ticks are
 * dropped by the caller (they are never passed to {@link #onTick}); this class is intentionally
 * unaware of staleness so it stays trivially unit-testable.
 *
 * <p>Band math is shared across both modes: {@code bandLow = T*(1-g)}, {@code bandHigh = T*(1+g)}.
 *
 * <ul>
 *   <li><b>BREAKOUT</b> ({@code g} = chase cap): ABOVE fires on {@code prev < T && T <= last <=
 *       bandHigh}; SKIP if the first cross past {@code T} is already {@code > bandHigh}. BELOW
 *       mirrored on {@code bandLow}.
 *   <li><b>RETEST</b> ({@code g} = re-test zone half-width {@code Z = [bandLow, bandHigh]}): ABOVE
 *       arms to BROKEN_OUT on {@code last > bandHigh}, then FIREs on a pull-back into {@code Z},
 *       and SKIPs on {@code last < bandLow} (support lost). BELOW mirrored.
 * </ul>
 *
 * <p>One-shot: once FIRE or SKIP is returned the machine is terminal and subsequent ticks return
 * that same terminal decision without re-evaluating.
 */
public final class EntryStateMachine {

  /** Whether the leg is a breakout (above) or breakdown (below) play. */
  public enum Direction {
    ABOVE,
    BELOW
  }

  /** Per-tick decision. NONE = keep waiting; FIRE/SKIP are terminal. */
  public enum Decision {
    NONE,
    FIRE,
    SKIP
  }

  /** Replay-stable machine state (carried across continue-as-new). */
  public enum State {
    ARMED,
    BROKEN_OUT,
    FIRED,
    SKIPPED
  }

  private final StrategyConfig.EntryMode mode;
  private final Direction direction;
  private final BigDecimal level;
  private final BigDecimal bandLow;
  private final BigDecimal bandHigh;

  private State state;
  private BigDecimal prev;

  public EntryStateMachine(
      StrategyConfig.EntryMode mode, Direction direction, BigDecimal level, BigDecimal gapTol) {
    if (mode == null || direction == null || level == null || gapTol == null) {
      throw new IllegalArgumentException("EntryStateMachine requires non-null args");
    }
    this.mode = mode;
    this.direction = direction;
    this.level = level;
    BigDecimal one = BigDecimal.ONE;
    this.bandLow = level.multiply(one.subtract(gapTol));
    this.bandHigh = level.multiply(one.add(gapTol));
    this.state = State.ARMED;
    this.prev = null;
  }

  /** Maps the {@link WatchlistTriggerPayload.Direction} enum onto this machine's direction. */
  public static Direction directionOf(WatchlistTriggerPayload.Direction d) {
    return d == WatchlistTriggerPayload.Direction.BELOW ? Direction.BELOW : Direction.ABOVE;
  }

  public State state() {
    return state;
  }

  public BigDecimal prev() {
    return prev;
  }

  /** Rehydrates the machine from carried continue-as-new state. Deterministic: no clock, no RNG. */
  public void seed(State carriedState, BigDecimal carriedPrev) {
    if (carriedState != null) {
      this.state = carriedState;
    }
    this.prev = carriedPrev;
  }

  /** Feeds the next (non-stale) trade price and returns the resulting decision. */
  public Decision onTick(BigDecimal last) {
    if (last == null) {
      return terminalDecision();
    }
    if (state == State.FIRED) {
      return Decision.FIRE;
    }
    if (state == State.SKIPPED) {
      return Decision.SKIP;
    }

    Decision decision =
        mode == StrategyConfig.EntryMode.RETEST ? evaluateRetest(last) : evaluateBreakout(last);
    this.prev = last;
    return decision;
  }

  private Decision terminalDecision() {
    if (state == State.FIRED) {
      return Decision.FIRE;
    }
    if (state == State.SKIPPED) {
      return Decision.SKIP;
    }
    return Decision.NONE;
  }

  private Decision evaluateBreakout(BigDecimal last) {
    // Need an observed live cross: the first tick only seeds prev.
    if (prev == null) {
      return Decision.NONE;
    }
    if (direction == Direction.ABOVE) {
      boolean crossed = prev.compareTo(level) < 0 && last.compareTo(level) >= 0;
      if (crossed) {
        if (last.compareTo(bandHigh) <= 0) {
          state = State.FIRED;
          return Decision.FIRE;
        }
        // Gapped/ran past the chase cap on the live cross -> don't chase.
        state = State.SKIPPED;
        return Decision.SKIP;
      }
      return Decision.NONE;
    }
    // BELOW (mirror)
    boolean crossed = prev.compareTo(level) > 0 && last.compareTo(level) <= 0;
    if (crossed) {
      if (last.compareTo(bandLow) >= 0) {
        state = State.FIRED;
        return Decision.FIRE;
      }
      state = State.SKIPPED;
      return Decision.SKIP;
    }
    return Decision.NONE;
  }

  private Decision evaluateRetest(BigDecimal last) {
    // No prev==null guard (unlike evaluateBreakout): RETEST does not compare prev against the
    // level.
    // A fire requires reaching BROKEN_OUT, and the only path into BROKEN_OUT is a prior tick that
    // cleared the band (last > bandHigh, or < bandLow for BELOW) while ARMED. So the live-cross
    // guarantee holds even across continue-as-new: a seeded BROKEN_OUT state by definition implies
    // a
    // prior live tick beyond the band, and a freshly resumed ARMED state still cannot fire until it
    // first clears the band. The first post-resume tick is never evaluated against a missing prev.
    if (direction == Direction.ABOVE) {
      if (state == State.ARMED) {
        // Clear the zone (a confirmed breakout) before any pull-back can fire.
        if (last.compareTo(bandHigh) > 0) {
          state = State.BROKEN_OUT;
        }
        return Decision.NONE;
      }
      // BROKEN_OUT: fire on pull-back into Z, skip if support lost below bandLow.
      if (last.compareTo(bandLow) < 0) {
        state = State.SKIPPED;
        return Decision.SKIP;
      }
      if (last.compareTo(bandHigh) <= 0) {
        state = State.FIRED;
        return Decision.FIRE;
      }
      return Decision.NONE;
    }
    // BELOW (mirror)
    if (state == State.ARMED) {
      if (last.compareTo(bandLow) < 0) {
        state = State.BROKEN_OUT;
      }
      return Decision.NONE;
    }
    if (last.compareTo(bandHigh) > 0) {
      state = State.SKIPPED;
      return Decision.SKIP;
    }
    if (last.compareTo(bandLow) >= 0) {
      state = State.FIRED;
      return Decision.FIRE;
    }
    return Decision.NONE;
  }
}
