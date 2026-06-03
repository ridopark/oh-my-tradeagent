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
   * Whether the US equity options market is currently open. Phase 5 KISS: Monday-Friday 09:30-16:00
   * America/New_York. Holidays and half-days land with the full MarketCalendar in Phase 6.
   */
  boolean isMarketOpen();

  /** Today's date in America/New_York. */
  LocalDate todayEt();
}
