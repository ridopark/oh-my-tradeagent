package com.ohmytradeagent.orchestrator.activities;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.stereotype.Component;

@Component
public class MarketCalendarActivitiesImpl implements MarketCalendarActivities {

  static final ZoneId ET = ZoneId.of("America/New_York");
  static final LocalTime EOD_TIME = LocalTime.of(15, 55);
  static final LocalTime EXPIRY_CLOSE_TIME = LocalTime.of(15, 30);
  static final LocalTime MARKET_OPEN_TIME = LocalTime.of(9, 30);
  static final LocalTime MARKET_CLOSE_TIME = LocalTime.of(16, 0);

  private final Clock clock;

  public MarketCalendarActivitiesImpl(Clock clock) {
    this.clock = clock;
  }

  @Override
  public Duration durationUntilEodEt() {
    ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(ET);
    ZonedDateTime eod = now.with(EOD_TIME).withSecond(0).withNano(0);
    if (!now.isBefore(eod)) {
      return Duration.ZERO;
    }
    return Duration.between(now, eod);
  }

  @Override
  public Duration durationUntilExpiryCloseEt(LocalDate expiry) {
    ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(ET);
    if (!expiry.equals(now.toLocalDate())) {
      return Duration.ZERO;
    }
    ZonedDateTime close = now.with(EXPIRY_CLOSE_TIME).withSecond(0).withNano(0);
    if (!now.isBefore(close)) {
      return Duration.ZERO;
    }
    return Duration.between(now, close);
  }

  @Override
  public boolean isMarketOpen() {
    ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(ET);
    DayOfWeek dow = now.getDayOfWeek();
    if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
      return false;
    }
    LocalTime t = now.toLocalTime();
    return !t.isBefore(MARKET_OPEN_TIME) && t.isBefore(MARKET_CLOSE_TIME);
  }

  @Override
  public LocalDate todayEt() {
    return ZonedDateTime.now(clock).withZoneSameInstant(ET).toLocalDate();
  }
}
