package com.ohmytradeagent.orchestrator.activities;

import io.temporal.activity.ActivityInterface;
import java.time.Duration;
import java.time.LocalDate;

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
   * Duration from "now" to 15:30 ET on {@code expiry}; ZERO if expiry is not today or already past.
   * Phase 3 only arms an expiry timer for 0DTE; future-dated expiries return ZERO so the workflow
   * can treat "no expiry timer" uniformly.
   */
  Duration durationUntilExpiryCloseEt(LocalDate expiry);

  /**
   * Whether the US equity options market is currently open. Phase 5 KISS: Monday-Friday 09:30-16:00
   * America/New_York. Holidays and half-days land with the full MarketCalendar in Phase 6.
   */
  boolean isMarketOpen();

  /** Today's date in America/New_York. */
  LocalDate todayEt();
}
