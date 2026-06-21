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

  // ---------- Phase 4: configurable blanket-EOD force-flatten time ----------

  @Test
  void eodConfigured_beforeConfiguredTime_returnsPositiveDuration() {
    MarketCalendarActivitiesImpl svc =
        new MarketCalendarActivitiesImpl(clockAtEt(2026, 5, 14, 9, 30));

    // Configured 15:30 ET instead of the legacy 15:55 -> 6h from 9:30 to 15:30.
    Duration d = svc.durationUntilEodCloseEt(LocalTime.of(15, 30));

    assertThat(d).isEqualTo(Duration.ofHours(6));
  }

  @Test
  void eodConfigured_pastConfiguredTime_returnsZero() {
    // 15:40 ET is already past a configured 15:30 EOD even though it precedes the legacy 15:55.
    MarketCalendarActivitiesImpl svc =
        new MarketCalendarActivitiesImpl(clockAtEt(2026, 5, 14, 15, 40));

    Duration d = svc.durationUntilEodCloseEt(LocalTime.of(15, 30));

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

  // ---------- Plan-2B R-AB-1: durationUntilExpiryFlattenEt (multi-day flatten lead) ----------

  @Test
  void flatten_multiDayExpiry_returnsPositiveDuration() {
    // 2026-05-14 is a Thursday; expiry 2026-05-16 is a Saturday — so the trigger rolls back to
    // Friday 2026-05-15. With closeTime=15:30 and lead=30, trigger = Fri 15:00 ET.
    MarketCalendarActivitiesImpl svc =
        new MarketCalendarActivitiesImpl(clockAtEt(2026, 5, 14, 10, 0));

    Duration d = svc.durationUntilExpiryFlattenEt(LocalDate.of(2026, 5, 15), 30L, null);

    // 2026-05-15 is a Friday: trigger = 15:30 - 30m = 15:00 ET. From Thu 10:00 to Fri 15:00 = 29h.
    assertThat(d).isPositive();
    assertThat(d).isEqualTo(Duration.ofHours(29));
  }

  @Test
  void flatten_sameDayBeforeLead_returnsPositive() {
    MarketCalendarActivitiesImpl svc =
        new MarketCalendarActivitiesImpl(clockAtEt(2026, 5, 15, 10, 0));

    // Fri 2026-05-15, close 15:30 default, lead 30 -> trigger 15:00. From 10:00 = 5h.
    Duration d = svc.durationUntilExpiryFlattenEt(LocalDate.of(2026, 5, 15), 30L, null);

    assertThat(d).isEqualTo(Duration.ofHours(5));
  }

  @Test
  void flatten_pastLeadTrigger_returnsZero() {
    MarketCalendarActivitiesImpl svc =
        new MarketCalendarActivitiesImpl(clockAtEt(2026, 5, 15, 15, 30));

    // Already past the 15:00 trigger.
    Duration d = svc.durationUntilExpiryFlattenEt(LocalDate.of(2026, 5, 15), 30L, null);

    assertThat(d).isEqualTo(Duration.ZERO);
  }

  @Test
  void flatten_configuredCloseTime_honored() {
    MarketCalendarActivitiesImpl svc =
        new MarketCalendarActivitiesImpl(clockAtEt(2026, 5, 15, 10, 0));

    // closeTime 14:45, lead 30 -> trigger 14:15 ET. From Fri 10:00 = 4h15m.
    Duration d =
        svc.durationUntilExpiryFlattenEt(LocalDate.of(2026, 5, 15), 30L, LocalTime.of(14, 45));

    assertThat(d).isEqualTo(Duration.ofHours(4).plusMinutes(15));
  }

  @Test
  void flatten_saturdayExpiry_rollsBackToFriday() {
    // Expiry on Saturday 2026-05-16 rolls back to Friday 2026-05-15. Clock = Mon 2026-05-11 10:00.
    MarketCalendarActivitiesImpl svc =
        new MarketCalendarActivitiesImpl(clockAtEt(2026, 5, 11, 10, 0));

    Duration dSat = svc.durationUntilExpiryFlattenEt(LocalDate.of(2026, 5, 16), 30L, null);
    Duration dFri = svc.durationUntilExpiryFlattenEt(LocalDate.of(2026, 5, 15), 30L, null);

    // Saturday expiry arms at the same instant as the prior Friday's trigger.
    assertThat(dSat).isEqualTo(dFri);
    assertThat(dSat).isPositive();
  }

  @Test
  void flatten_zeroLead_armsAtCloseTime() {
    MarketCalendarActivitiesImpl svc =
        new MarketCalendarActivitiesImpl(clockAtEt(2026, 5, 15, 10, 0));

    // lead=0 -> trigger == closeTime (15:30). From Fri 10:00 = 5h30m.
    Duration d = svc.durationUntilExpiryFlattenEt(LocalDate.of(2026, 5, 15), 0L, null);

    assertThat(d).isEqualTo(Duration.ofHours(5).plusMinutes(30));
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
