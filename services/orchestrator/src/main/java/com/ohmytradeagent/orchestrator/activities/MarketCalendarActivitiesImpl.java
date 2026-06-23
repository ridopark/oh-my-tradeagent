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
    return durationUntilEodCloseEt(EOD_TIME);
  }

  @Override
  public Duration durationUntilEodCloseEt(LocalTime eodEt) {
    ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(ET);
    ZonedDateTime eod = now.with(eodEt).withSecond(0).withNano(0);
    if (!now.isBefore(eod)) {
      return Duration.ZERO;
    }
    return Duration.between(now, eod);
  }

  @Override
  public Duration durationUntilExpiryCloseEt(LocalDate expiry, LocalTime closeTime) {
    // Issue #15: null closeTime preserves the legacy 15:30 ET default.
    LocalTime effectiveClose = closeTime != null ? closeTime : EXPIRY_CLOSE_TIME;
    ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(ET);
    if (!expiry.equals(now.toLocalDate())) {
      return Duration.ZERO;
    }
    ZonedDateTime close = now.with(effectiveClose).withSecond(0).withNano(0);
    if (!now.isBefore(close)) {
      return Duration.ZERO;
    }
    return Duration.between(now, close);
  }

  @Override
  public Duration durationUntilExpiryFlattenEt(
      LocalDate expiry, long leadMinutes, LocalTime closeTime) {
    // Plan-2B R-AB-1: arm a flatten lead timer for ANY future-or-today expiry (multi-day included),
    // unlike durationUntilExpiryCloseEt which is 0DTE-only.
    LocalTime effectiveClose = closeTime != null ? closeTime : EXPIRY_CLOSE_TIME;
    long lead = Math.max(0L, leadMinutes);
    ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(ET);

    // Trigger = (expiry close - leadMinutes) ET, on the expiry date.
    ZonedDateTime trigger =
        ZonedDateTime.of(expiry, effectiveClose, ET).withSecond(0).withNano(0).minusMinutes(lead);

    // Weekend roll-back: a Saturday/Sunday trigger is moved to the same wall-clock time on the
    // prior Friday so the bounded flatten arms before the weekend rather than for a closed market.
    DayOfWeek dow = trigger.getDayOfWeek();
    if (dow == DayOfWeek.SATURDAY) {
      trigger = trigger.minusDays(1);
    } else if (dow == DayOfWeek.SUNDAY) {
      trigger = trigger.minusDays(2);
    }

    if (!now.isBefore(trigger)) {
      return Duration.ZERO;
    }
    return Duration.between(now, trigger);
  }

  @Override
  public Duration durationUntilRthOpenEt() {
    ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(ET);
    DayOfWeek dow = now.getDayOfWeek();
    if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
      return Duration.ZERO;
    }
    ZonedDateTime open = now.with(MARKET_OPEN_TIME).withSecond(0).withNano(0);
    if (!now.isBefore(open)) {
      return Duration.ZERO;
    }
    return Duration.between(now, open);
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
