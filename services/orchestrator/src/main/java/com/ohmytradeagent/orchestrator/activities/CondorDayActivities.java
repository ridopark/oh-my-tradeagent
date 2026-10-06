package com.ohmytradeagent.orchestrator.activities;

import io.temporal.activity.ActivityInterface;
import java.time.LocalDate;

/**
 * Gated-condor Phase 4 day filter, run in-process on the orchestrator-core queue. Keeps the FOMC
 * calendar file read and the half-day rules off the deterministic workflow path. Market-closed days
 * are NOT decided here — the session asks the broker's trading calendar ({@code
 * MarketCalendarActivity.tradingDays}) for those.
 */
@ActivityInterface
public interface CondorDayActivities {

  /**
   * Why the condor sits out {@code etDate}, or null to trade it: {@code half_day} on an NYSE early
   * close (13:00 ET — the 14:00 entry is after the close), {@code fomc} on an FOMC statement day
   * when {@code skipEventDays} (the U3 risk edge: gated FOMC afternoons had a −100% worst).
   */
  String eventSkipReason(LocalDate etDate, boolean skipEventDays);
}
