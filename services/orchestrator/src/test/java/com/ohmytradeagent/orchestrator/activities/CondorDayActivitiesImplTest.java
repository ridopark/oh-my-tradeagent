package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CondorDayActivitiesImplTest {

  private final CondorDayActivitiesImpl activity = new CondorDayActivitiesImpl();

  @Test
  void fomcStatementDay_fromThePackagedCalendar_isSkipped() {
    // scripts/data/fomc-dates.txt, packaged onto the classpath by the orchestrator pom.
    assertThat(activity.eventSkipReason(LocalDate.of(2026, 10, 28), true)).isEqualTo("fomc");
    assertThat(activity.eventSkipReason(LocalDate.of(2026, 9, 16), true)).isEqualTo("fomc");
  }

  @Test
  void fomcDay_tradesOnlyWhenEventSkipExplicitlyOff() {
    assertThat(activity.eventSkipReason(LocalDate.of(2026, 10, 28), false)).isNull();
  }

  @Test
  void ordinaryDay_isNotSkipped() {
    assertThat(activity.eventSkipReason(LocalDate.of(2026, 10, 5), true)).isNull();
  }

  @Test
  void earlyCloseDays_areSkippedRegardlessOfEventFlag() {
    assertThat(activity.eventSkipReason(LocalDate.of(2026, 11, 27), false)).isEqualTo("half_day");
    assertThat(activity.eventSkipReason(LocalDate.of(2026, 12, 24), false)).isEqualTo("half_day");
    assertThat(activity.eventSkipReason(LocalDate.of(2025, 7, 3), false)).isEqualTo("half_day");
  }

  @Test
  void fridayJuly3_isTheObservedHoliday_notAHalfDay() {
    assertThat(CondorDayActivitiesImpl.isHalfDay(LocalDate.of(2026, 7, 3))).isFalse();
    assertThat(CondorDayActivitiesImpl.isHalfDay(LocalDate.of(2026, 11, 26))).isFalse();
  }
}
