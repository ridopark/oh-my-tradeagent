package com.ohmytradeagent.marketdata;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * The ONE regular-trading-hours check for market-data: Mon–Fri 09:30–16:00 ET, weekday-only —
 * extracted verbatim from {@code SubscribeEquityActivityImpl.isRegularTradingHours} so the #776
 * boot recovery gate and the equity subscribe gate cannot drift apart (the plan forbids a third
 * copy).
 *
 * <p>Known residual, shared with the equity gate it was extracted from: weekday-only, NO holiday
 * calendar. On a market holiday callers treat the day as a trading day; accepted because it matches
 * the estate's existing gate semantics and the alternative is a holiday-calendar dependency.
 *
 * <p>Public (not package-private as first sketched) because the two consumers live in different
 * packages ({@code activities} and {@code recovery}).
 */
public final class MarketHours {

  public static final ZoneId ET = ZoneId.of("America/New_York");
  static final LocalTime RTH_OPEN = LocalTime.of(9, 30);
  static final LocalTime RTH_CLOSE = LocalTime.of(16, 0);

  private MarketHours() {}

  /** Mon–Fri, 09:30 inclusive to 16:00 exclusive, evaluated on an ET-zoned instant. */
  public static boolean isRegularTradingHours(ZonedDateTime nowEt) {
    if (!isWeekday(nowEt)) {
      return false;
    }
    LocalTime t = nowEt.toLocalTime();
    return !t.isBefore(RTH_OPEN) && t.isBefore(RTH_CLOSE);
  }

  /**
   * The next RTH open strictly at-or-after {@code nowEt}: today 09:30 when it is a weekday morning
   * before the open, otherwise 09:30 of the next weekday. Saturday/Sunday roll to Monday.
   */
  public static ZonedDateTime nextRthOpen(ZonedDateTime nowEt) {
    ZonedDateTime day = nowEt;
    if (!isWeekday(day) || !day.toLocalTime().isBefore(RTH_OPEN)) {
      do {
        day = day.plusDays(1);
      } while (!isWeekday(day));
    }
    return day.toLocalDate().atTime(RTH_OPEN).atZone(day.getZone());
  }

  private static boolean isWeekday(ZonedDateTime d) {
    DayOfWeek dow = d.getDayOfWeek();
    return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
  }
}
