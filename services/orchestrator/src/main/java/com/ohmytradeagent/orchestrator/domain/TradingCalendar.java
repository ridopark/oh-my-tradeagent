package com.ohmytradeagent.orchestrator.domain;

import java.time.LocalDate;

/**
 * Trading-day oracle for {@link ExpirySelector}. Decouples the pure expiry rule from the network
 * source of holiday/half-day truth (the Alpaca trading-calendar API, fetched behind a Temporal
 * activity). Implementations resolve whether a given date is a US-equity-options trading day.
 */
@FunctionalInterface
public interface TradingCalendar {

  boolean isTradingDay(LocalDate date);
}
