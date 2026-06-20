package com.ohmytradeagent.orchestrator.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link ExpirySelector}. The trading-day oracle is stubbed in-memory (no
 * network), so the Friday rule and the holiday preceding-trading-day shift are exercised in
 * isolation.
 */
class ExpirySelectorTest {

  /** A {@link TradingCalendar} that treats every supplied date as a trading day. */
  private static TradingCalendar allTradingDays() {
    return date -> true;
  }

  /** A {@link TradingCalendar} that treats every date as a trading day EXCEPT the holidays. */
  private static TradingCalendar holidaysExcluded(LocalDate... holidays) {
    Set<LocalDate> holidaySet = Set.of(holidays);
    return date -> !holidaySet.contains(date);
  }

  @Test
  void midWeekReference_resolvesToThisWeeksFriday() {
    // Wednesday 2026-06-17 -> Friday 2026-06-19.
    LocalDate resolved =
        ExpirySelector.resolveNearestWeekly(LocalDate.of(2026, 6, 17), false, allTradingDays());

    assertThat(resolved).isEqualTo(LocalDate.of(2026, 6, 19));
  }

  @Test
  void fridayAfterClose_resolvesToNextFriday() {
    // Friday 2026-06-19 after close -> next Friday 2026-06-26.
    LocalDate resolved =
        ExpirySelector.resolveNearestWeekly(LocalDate.of(2026, 6, 19), true, allTradingDays());

    assertThat(resolved).isEqualTo(LocalDate.of(2026, 6, 26));
  }

  @Test
  void fridayBeforeClose_resolvesToSameFriday() {
    // Friday 2026-06-19 mid-session (not after close) -> same Friday.
    LocalDate resolved =
        ExpirySelector.resolveNearestWeekly(LocalDate.of(2026, 6, 19), false, allTradingDays());

    assertThat(resolved).isEqualTo(LocalDate.of(2026, 6, 19));
  }

  @Test
  void weekend_resolvesToNextFriday() {
    // Saturday 2026-06-20 -> next Friday 2026-06-26.
    LocalDate saturday =
        ExpirySelector.resolveNearestWeekly(LocalDate.of(2026, 6, 20), false, allTradingDays());
    assertThat(saturday).isEqualTo(LocalDate.of(2026, 6, 26));

    // Sunday 2026-06-21 -> next Friday 2026-06-26.
    LocalDate sunday =
        ExpirySelector.resolveNearestWeekly(LocalDate.of(2026, 6, 21), false, allTradingDays());
    assertThat(sunday).isEqualTo(LocalDate.of(2026, 6, 26));
  }

  @Test
  void holidayFriday_shiftsToPrecedingThursday() {
    // Friday 2026-07-03 is a market holiday (observed July 4). Thursday 2026-07-02 trades.
    // Mid-week reference Tuesday 2026-06-30 -> candidate Friday 2026-07-03 (holiday) -> Thu 07-02.
    TradingCalendar cal = holidaysExcluded(LocalDate.of(2026, 7, 3));

    LocalDate resolved = ExpirySelector.resolveNearestWeekly(LocalDate.of(2026, 6, 30), false, cal);

    assertThat(resolved).isEqualTo(LocalDate.of(2026, 7, 2));
  }

  @Test
  void holidayFridayAndThursday_shiftsBackToWednesday() {
    // Both Friday 2026-07-03 and Thursday 2026-07-02 are non-trading -> Wednesday 2026-07-01.
    TradingCalendar cal = holidaysExcluded(LocalDate.of(2026, 7, 3), LocalDate.of(2026, 7, 2));

    LocalDate resolved = ExpirySelector.resolveNearestWeekly(LocalDate.of(2026, 6, 30), false, cal);

    assertThat(resolved).isEqualTo(LocalDate.of(2026, 7, 1));
  }

  @Test
  void arbitraryFutureDate_isNotHardcodedToOneFriday() {
    // Thursday 2026-09-10 -> Friday 2026-09-11, several weeks past the other cases.
    LocalDate resolved =
        ExpirySelector.resolveNearestWeekly(LocalDate.of(2026, 9, 10), false, allTradingDays());

    assertThat(resolved).isEqualTo(LocalDate.of(2026, 9, 11));
  }
}
