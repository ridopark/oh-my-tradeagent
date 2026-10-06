package com.ohmytradeagent.orchestrator.activities;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Reads the FOMC statement-day calendar from the classpath resource {@value #FOMC_RESOURCE} — the
 * SAME file the research and scripts use ({@code scripts/data/fomc-dates.txt}, packaged into the
 * orchestrator jar by its pom, so there is one copy to maintain). Fail-closed: a missing or
 * unreadable file throws at construction, so the orchestrator cannot silently trade FOMC days.
 */
@Component
public class CondorDayActivitiesImpl implements CondorDayActivities {

  static final String FOMC_RESOURCE = "/fomc-dates.txt";

  private final Set<LocalDate> fomcDays;

  public CondorDayActivitiesImpl() {
    this.fomcDays = loadFomcDays();
  }

  @Override
  public String eventSkipReason(LocalDate etDate, boolean skipEventDays) {
    if (isHalfDay(etDate)) {
      return "half_day";
    }
    if (skipEventDays && fomcDays.contains(etDate)) {
      return "fomc";
    }
    return null;
  }

  /**
   * NYSE early closes (13:00 ET): the day after Thanksgiving; Christmas Eve on a Monday-Thursday;
   * July 3 on a Monday-Thursday (a Friday July 3 is the observed Independence Day holiday, which
   * the broker calendar reports closed). A non-trading date the broker calendar already rejects is
   * irrelevant here.
   */
  static boolean isHalfDay(LocalDate d) {
    LocalDate thanksgiving =
        LocalDate.of(d.getYear(), Month.NOVEMBER, 1)
            .with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.THURSDAY));
    if (d.equals(thanksgiving.plusDays(1))) {
      return true;
    }
    boolean monToThu = d.getDayOfWeek().getValue() <= DayOfWeek.THURSDAY.getValue();
    return monToThu
        && ((d.getMonth() == Month.DECEMBER && d.getDayOfMonth() == 24)
            || (d.getMonth() == Month.JULY && d.getDayOfMonth() == 3));
  }

  static Set<LocalDate> loadFomcDays() {
    try (InputStream in = CondorDayActivitiesImpl.class.getResourceAsStream(FOMC_RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("classpath resource " + FOMC_RESOURCE + " is missing");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8)
          .lines()
          .map(String::trim)
          .filter(l -> !l.isEmpty() && !l.startsWith("#"))
          .map(LocalDate::parse)
          .collect(Collectors.toUnmodifiableSet());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
