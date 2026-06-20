package com.ohmytradeagent.contract.activities;

import io.temporal.activity.ActivityInterface;
import java.time.LocalDate;
import java.util.List;

/**
 * Cross-service contract for the Alpaca trading-calendar lookup. The implementation lives in {@code
 * services/exec} (it already speaks the Alpaca trading API and holds the per-account credentials);
 * consumers (the watchlist-trigger workflow) declare a workflow stub against this interface on the
 * exec task queue. Temporal routes by activity name + task queue — no shared bytecode required.
 *
 * <p>Backed by {@code GET /v2/calendar?start=&end=}, a basic-account trading endpoint (NOT a
 * real-time market-data entitlement). Returns the trading days in {@code [start, end]} inclusive so
 * the workflow can build a {@code TradingCalendar} oracle for the pure {@code ExpirySelector}.
 */
@ActivityInterface
public interface MarketCalendarActivity {

  /** Trading days in {@code [start, end]} inclusive, per the Alpaca trading calendar. */
  List<LocalDate> tradingDays(LocalDate start, LocalDate end);
}
