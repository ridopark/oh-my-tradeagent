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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Reads the FOMC statement-day calendar from the classpath resource {@value #FOMC_RESOURCE} — the
 * SAME file the research and scripts use ({@code scripts/data/fomc-dates.txt}, packaged into the
 * orchestrator jar by its pom, so there is one copy to maintain). Fail-closed but contained (#901):
 * a missing or unreadable file throws from {@link #eventSkipReason}, failing only the condor
 * session's day — never bean construction, which would crash-loop the whole orchestrator.
 */
@Component
public class CondorDayActivitiesImpl implements CondorDayActivities {

  static final String FOMC_RESOURCE = "/fomc-dates.txt";

  private final String resource;
  private volatile Set<LocalDate> fomcDays;

  @Autowired
  public CondorDayActivitiesImpl() {
    this(FOMC_RESOURCE);
  }

  CondorDayActivitiesImpl(String resource) {
    this.resource = resource;
  }

  @Override
  public String eventSkipReason(LocalDate etDate, boolean skipEventDays) {
    Set<LocalDate> fomc = fomcDays;
    if (fomc == null) {
      // Loaded on every call until it succeeds; read before the half-day check so a missing file
      // is surfaced on the first session rather than hidden behind a half-day skip.
      fomc = loadFomcDays(resource);
      fomcDays = fomc;
    }
    if (isHalfDay(etDate)) {
      return "half_day";
    }
    if (skipEventDays && fomc.contains(etDate)) {
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

  static Set<LocalDate> loadFomcDays(String resource) {
    try (InputStream in = CondorDayActivitiesImpl.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("classpath resource " + resource + " is missing");
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
