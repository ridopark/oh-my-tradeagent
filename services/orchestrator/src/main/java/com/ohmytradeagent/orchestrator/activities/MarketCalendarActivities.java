package com.ohmytradeagent.orchestrator.activities;

import io.temporal.activity.ActivityInterface;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Computes deterministic-from-workflow durations to EOD and to option expiry close in ET.
 *
 * <p>Workflow code is forbidden from reading wall-clock time. This Activity wraps {@link
 * java.time.Clock} so that PositionWorkflow can ask "how long until 15:55 ET?" without breaking
 * replay determinism. Phase 3 deliberately ships a KISS implementation — no holiday handling, no
 * half-day awareness; those land with the full MarketCalendar in Phase 0b complete + Phase 5 ops.
 */
@ActivityInterface
public interface MarketCalendarActivities {

  /** Duration from "now" (Activity wall clock) to today's 15:55 ET; ZERO if already past. */
  Duration durationUntilEodEt();

  /**
   * Phase 4: duration from "now" (Activity wall clock) to today's {@code eodEt} ET; ZERO if already
   * past. Lets PositionWorkflow force-flatten at a configured wall-clock time (e.g. 15:30 ET)
   * instead of the hardcoded legacy 15:55. Same ET / already-past / market-day logic as the no-arg
   * {@link #durationUntilEodEt()} and {@link #durationUntilExpiryCloseEt}; the {@code eodEt}
   * argument flows only through the (replay-ignored) activity input payload, so the Temporal
   * activity type name {@code DurationUntilEodEt} stays distinct from the no-arg overload's and no
   * Workflow.getVersion gate is needed.
   */
  Duration durationUntilEodEt(LocalTime eodEt);

  /**
   * Duration from "now" to {@code closeTime} ET on {@code expiry}; ZERO if expiry is not today or
   * already past. Phase 3 only arms an expiry timer for 0DTE; future-dated expiries return ZERO so
   * the workflow can treat "no expiry timer" uniformly.
   *
   * <p>Issue #15: {@code closeTime} is the per-strategy {@code force_close_0dte_et} override. A
   * null {@code closeTime} preserves the legacy 15:30 ET default. The method name (and therefore
   * the Temporal activity type name {@code DurationUntilExpiryCloseEt}) is unchanged from the
   * original single-arg signature, so in-flight PositionWorkflow replays match the recorded
   * ActivityTaskScheduled command — the added argument flows only through the (replay-ignored)
   * activity input payload and needs no Workflow.getVersion gate.
   */
  Duration durationUntilExpiryCloseEt(LocalDate expiry, LocalTime closeTime);

  /**
   * Plan-2B R-AB-1: duration from "now" (Activity wall clock) to {@code (closeTime - leadMinutes)}
   * ET on {@code expiry}, for ANY future-or-today expiry (NOT just 0DTE — this is the multi-day
   * sell-guarantee surface that {@link #durationUntilExpiryCloseEt} cannot provide because it
   * returns ZERO unless {@code expiry == today}).
   *
   * <p>ET-aware / weekend-aware: when the computed lead instant lands on a Saturday or Sunday, the
   * trigger is rolled FORWARD to the same wall-clock time on the prior Friday so the bounded
   * flatten still arms before the weekend (a flatten firing on a closed market is a safe no-op
   * anyway — the bounded limit simply does not fill until the next session — so holiday-awareness
   * is descoped per the plan; weekend roll-back is the minimum to avoid arming a timer for a
   * non-trading day).
   *
   * <p>Returns a POSITIVE Duration for any future trigger; ZERO when the trigger instant is already
   * past (the caller then treats the lot as already inside its flatten window and arms no timer —
   * the EOD/expiry-close timers cover the same-session case). Determinism: the value flows only
   * through the (replay-ignored) Activity input payload; the workflow gates the timer-arm command
   * itself via {@code Workflow.getVersion}.
   *
   * @param expiry the option's OCC expiry date
   * @param leadMinutes minutes before the close at which to arm the flatten (e.g. 30)
   * @param closeTime per-strategy close time ET; null preserves the legacy 15:30 ET default
   */
  Duration durationUntilExpiryFlattenEt(LocalDate expiry, long leadMinutes, LocalTime closeTime);

  /**
   * Whether the US equity options market is currently open. Phase 5 KISS: Monday-Friday 09:30-16:00
   * America/New_York. Holidays and half-days land with the full MarketCalendar in Phase 6.
   */
  boolean isMarketOpen();

  /** Today's date in America/New_York. */
  LocalDate todayEt();
}
