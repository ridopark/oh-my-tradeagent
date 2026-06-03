package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class MarketCalendarActivitiesImplTest {

  private static final ZoneId ET = ZoneId.of("America/New_York");

  private static Clock clockAtEt(int year, int month, int day, int hour, int minute) {
    Instant t = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ET).toInstant();
    return Clock.fixed(t, ZoneOffset.UTC);
  }

  @Test
  void eod_beforeFifteenFiftyFive_returnsPositiveDuration() {
    MarketCalendarActivitiesImpl svc =
        new MarketCalendarActivitiesImpl(clockAtEt(2026, 5, 14, 9, 30));

    Duration d = svc.durationUntilEodEt();

    // 6h25m from 9:30 ET to 15:55 ET
    assertThat(d).isEqualTo(Duration.ofHours(6).plusMinutes(25));
  }

  @Test
  void eod_pastFifteenFiftyFive_returnsZero() {
    MarketCalendarActivitiesImpl svc =
        new MarketCalendarActivitiesImpl(clockAtEt(2026, 5, 14, 16, 0));

    Duration d = svc.durationUntilEodEt();

    assertThat(d).isEqualTo(Duration.ZERO);
  }

  @Test
  void expiry_sameDayBeforeFifteenThirty_returnsPositive() {
    MarketCalendarActivitiesImpl svc =
        new MarketCalendarActivitiesImpl(clockAtEt(2026, 5, 16, 10, 0));

    // Issue #15: null closeTime preserves the legacy 15:30 ET default.
    Duration d = svc.durationUntilExpiryCloseEt(LocalDate.of(2026, 5, 16), null);

    // 5h30m from 10:00 to 15:30
    assertThat(d).isEqualTo(Duration.ofHours(5).plusMinutes(30));
  }

  @Test
  void expiry_dateInFuture_returnsZero() {
    // KISS: Phase 3 only fires expiry timer for 0DTE / same-day expiry. Future expiry => ZERO
    // means "don't bother arming the timer" — workflow checks expiryFired separately.
    MarketCalendarActivitiesImpl svc =
        new MarketCalendarActivitiesImpl(clockAtEt(2026, 5, 14, 10, 0));

    Duration d = svc.durationUntilExpiryCloseEt(LocalDate.of(2026, 5, 16), null);

    assertThat(d).isEqualTo(Duration.ZERO);
  }

  @Test
  void expiry_pastExpiryClose_returnsZero() {
    MarketCalendarActivitiesImpl svc =
        new MarketCalendarActivitiesImpl(clockAtEt(2026, 5, 16, 16, 0));

    Duration d = svc.durationUntilExpiryCloseEt(LocalDate.of(2026, 5, 16), null);

    assertThat(d).isEqualTo(Duration.ZERO);
  }

  // ---------- Issue #15: configurable force_close_0dte_et close time ----------

  @Test
  void expiry_configuredFourteenHundred_beforeTwoPm_returnsPositive() {
    MarketCalendarActivitiesImpl svc =
        new MarketCalendarActivitiesImpl(clockAtEt(2026, 5, 16, 10, 0));

    Duration d = svc.durationUntilExpiryCloseEt(LocalDate.of(2026, 5, 16), LocalTime.of(14, 0));

    // 4h from 10:00 to 14:00
    assertThat(d).isEqualTo(Duration.ofHours(4));
  }

  @Test
  void expiry_configuredFourteenHundred_afterTwoPm_returnsZero() {
    MarketCalendarActivitiesImpl svc =
        new MarketCalendarActivitiesImpl(clockAtEt(2026, 5, 16, 14, 30));

    Duration d = svc.durationUntilExpiryCloseEt(LocalDate.of(2026, 5, 16), LocalTime.of(14, 0));

    assertThat(d).isEqualTo(Duration.ZERO);
  }

  @Test
  void expiry_configuredFourteenHundred_dateInFuture_returnsZero() {
    MarketCalendarActivitiesImpl svc =
        new MarketCalendarActivitiesImpl(clockAtEt(2026, 5, 14, 10, 0));

    Duration d = svc.durationUntilExpiryCloseEt(LocalDate.of(2026, 5, 16), LocalTime.of(14, 0));

    assertThat(d).isEqualTo(Duration.ZERO);
  }

  @Test
  void isMarketOpen_weekdayDuringSession_returnsTrue() {
    // 2026-05-14 is a Thursday
    MarketCalendarActivitiesImpl svc =
        new MarketCalendarActivitiesImpl(clockAtEt(2026, 5, 14, 10, 0));

    assertThat(svc.isMarketOpen()).isTrue();
  }

  @Test
  void isMarketOpen_weekdayBeforeOpen_returnsFalse() {
    MarketCalendarActivitiesImpl svc =
        new MarketCalendarActivitiesImpl(clockAtEt(2026, 5, 14, 9, 0));

    assertThat(svc.isMarketOpen()).isFalse();
  }

  @Test
  void isMarketOpen_weekdayAfterClose_returnsFalse() {
    MarketCalendarActivitiesImpl svc =
        new MarketCalendarActivitiesImpl(clockAtEt(2026, 5, 14, 16, 0));

    assertThat(svc.isMarketOpen()).isFalse();
  }

  @Test
  void isMarketOpen_saturday_returnsFalse() {
    // 2026-05-16 is a Saturday
    MarketCalendarActivitiesImpl svc =
        new MarketCalendarActivitiesImpl(clockAtEt(2026, 5, 16, 11, 0));

    assertThat(svc.isMarketOpen()).isFalse();
  }

  @Test
  void todayEt_returnsLocalDateInEt() {
    // 2026-05-14 21:00 UTC is 2026-05-14 17:00 ET (DST in effect: UTC-4)
    MarketCalendarActivitiesImpl svc =
        new MarketCalendarActivitiesImpl(
            Clock.fixed(Instant.parse("2026-05-14T21:00:00Z"), ZoneOffset.UTC));

    assertThat(svc.todayEt()).isEqualTo(LocalDate.of(2026, 5, 14));
  }
}
