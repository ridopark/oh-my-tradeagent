package com.ohmytradeagent.orchestrator.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * Pure {@code NEAREST_WEEKLY} expiry resolver for the watchlist-trigger strategy. A watchlist play
 * carries NO expiry; this resolves a reference date to a concrete option-expiry {@link LocalDate}.
 *
 * <p>Rule (locked):
 *
 * <ul>
 *   <li>From the reference date, the candidate expiry is the upcoming weekly <b>Friday</b>:
 *       mid-week -> this Friday; Friday-after-close or a weekend -> next Friday.
 *   <li>If that Friday is NOT a trading day per the supplied {@link TradingCalendar} (a holiday),
 *       shift to the <b>preceding trading day</b> (e.g. a holiday Friday -> Thursday), matching the
 *       US option-expiration convention. The shift walks back day-by-day until a trading day is
 *       found, so a back-to-back holiday Thursday+Friday lands on Wednesday.
 * </ul>
 *
 * <p>Pure: no network, no {@code Instant.now()}. The {@code afterClose} flag encodes the
 * Friday-after-close case so the caller (the Phase 4 workflow) owns the wall-clock decision.
 */
public final class ExpirySelector {

  private ExpirySelector() {}

  /** Hard floor on the backward holiday walk; far exceeds any realistic US holiday cluster. */
  private static final int MAX_SHIFT_DAYS = 7;

  /**
   * Resolves the {@code NEAREST_WEEKLY} expiry for {@code reference}.
   *
   * @param reference the reference date (e.g. the trigger date in market-local time)
   * @param afterClose true when {@code reference} is a Friday whose session has already closed, so
   *     the candidate rolls to next Friday rather than the same Friday
   * @param calendar trading-day oracle used for the holiday preceding-trading-day shift
   * @return the resolved option-expiry date
   */
  public static LocalDate resolveNearestWeekly(
      LocalDate reference, boolean afterClose, TradingCalendar calendar) {
    LocalDate friday = upcomingFriday(reference, afterClose);
    return shiftToPrecedingTradingDay(friday, calendar);
  }

  private static LocalDate upcomingFriday(LocalDate reference, boolean afterClose) {
    DayOfWeek dow = reference.getDayOfWeek();
    if (dow == DayOfWeek.FRIDAY) {
      return afterClose ? reference.plusDays(7) : reference;
    }
    if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
      // Weekend -> next week's Friday.
      int daysUntilFriday = (DayOfWeek.FRIDAY.getValue() - dow.getValue() + 7) % 7;
      return reference.plusDays(daysUntilFriday);
    }
    // Mid-week (Mon-Thu) -> this week's Friday.
    return reference.plusDays(DayOfWeek.FRIDAY.getValue() - dow.getValue());
  }

  private static LocalDate shiftToPrecedingTradingDay(
      LocalDate candidate, TradingCalendar calendar) {
    LocalDate day = candidate;
    for (int i = 0; i < MAX_SHIFT_DAYS; i++) {
      if (calendar.isTradingDay(day)) {
        return day;
      }
      day = day.minusDays(1);
    }
    throw new IllegalStateException(
        "no trading day within "
            + MAX_SHIFT_DAYS
            + " days preceding candidate expiry "
            + candidate);
  }
}
