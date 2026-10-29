package com.ohmytradeagent.tdbff.darkpool;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly retention for the dark-pool store: prints older than {@value #PRINT_RETENTION_DAYS} days
 * and bars older than {@value #BAR_RETENTION_DAYS} days. Same shape as {@code
 * OptionsChatRetention}: a {@code @Scheduled} bean on the single-replica BFF that already owns the
 * schema, bounded per batch and per run, and never fatal.
 *
 * <p>The horizons are fixed rather than configurable: the plan sets them, and the one way a
 * configurable retention has gone wrong here before (a blank value computing a cutoff of "now") is
 * a failure mode this class simply does not have.
 */
@Component
@ConditionalOnProperty(
    name = {"darkpool.enabled", "dashboard.writer.enabled"},
    havingValue = "true")
public class DarkpoolRetention {

  private static final Logger log = LoggerFactory.getLogger(DarkpoolRetention.class);

  static final int PRINT_RETENTION_DAYS = 90;
  static final int BAR_RETENTION_DAYS = 400;

  /**
   * Rows per statement. Larger than the options-chat sweep's 500 because a print row is narrow and
   * one session can add tens of thousands of them; a night's cap must clear at least a day.
   */
  static final int BATCH = 5000;

  /** Batches per run per table, so one night's work is bounded even against a backlog. */
  static final int MAX_BATCHES = 200;

  private final DarkpoolRepository repo;
  private final Clock clock;

  /**
   * {@code @Autowired} is LOAD-BEARING: two constructors with none annotated aborts context refresh
   * ({@code SpringComponentConstructorGuardTest} and the writer-enabled smoke test pin it).
   */
  @Autowired
  public DarkpoolRetention(DarkpoolRepository repo) {
    this(repo, Clock.systemUTC());
  }

  DarkpoolRetention(DarkpoolRepository repo, Clock clock) {
    this.repo = repo;
    this.clock = clock;
  }

  /** 03:40 UTC daily — after the options-chat sweep, far from any US session. */
  @Scheduled(cron = "0 40 3 * * *", zone = "UTC")
  public void purge() {
    runOnce();
  }

  /** Returns how many rows went (prints + bars). Never throws. */
  int runOnce() {
    OffsetDateTime now = OffsetDateTime.now(clock);
    int prints = sweep("prints", now.minusDays(PRINT_RETENTION_DAYS), repo::deletePrintsOlderThan);
    int bars = sweep("bars", now.minusDays(BAR_RETENTION_DAYS), repo::deleteBarsOlderThan);
    return prints + bars;
  }

  private static int sweep(
      String what, OffsetDateTime cutoff, BiFunction<OffsetDateTime, Integer, Integer> delete) {
    int total = 0;
    try {
      for (int i = 0; i < MAX_BATCHES; i++) {
        int deleted = delete.apply(cutoff, BATCH);
        total += deleted;
        if (deleted < BATCH) {
          break;
        }
      }
    } catch (Exception e) { // noqa - housekeeping must never take the BFF down
      log.error(
          "darkpool {} retention failed after {} row(s); will retry tomorrow", what, total, e);
      return total;
    }
    if (total > 0) {
      log.info("darkpool retention removed {} {} before {}", total, what, cutoff);
      if (total >= BATCH * MAX_BATCHES) {
        log.warn(
            "darkpool {} retention hit its per-run cap ({}); the backlog drains on later nights",
            what,
            BATCH * MAX_BATCHES);
      }
    }
    return total;
  }
}
