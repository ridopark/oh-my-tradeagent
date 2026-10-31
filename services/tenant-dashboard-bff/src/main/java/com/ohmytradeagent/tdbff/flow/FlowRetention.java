package com.ohmytradeagent.tdbff.flow;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly retention for the flow store: orders older than {@value #ORDER_RETENTION_DAYS} days and
 * ticker-days older than {@value #TICKER_DAY_RETENTION_DAYS} days. Same shape as {@code
 * DarkpoolRetention}: a {@code @Scheduled} bean on the single-replica BFF that already owns the
 * schema, bounded per batch and per run, and never fatal.
 *
 * <p>The horizons are fixed rather than configurable: the plan sets them, and the one way a
 * configurable retention has gone wrong here before (a blank value computing a cutoff of "now") is
 * a failure mode this class simply does not have.
 */
@Component
@ConditionalOnProperty(
    name = {"flow.enabled", "dashboard.writer.enabled"},
    havingValue = "true")
public class FlowRetention {

  private static final Logger log = LoggerFactory.getLogger(FlowRetention.class);

  static final int ORDER_RETENTION_DAYS = 90;
  static final int TICKER_DAY_RETENTION_DAYS = 730;

  /**
   * Rows per statement. Larger than the options-chat sweep's 500 because an order row is narrow and
   * one session can add many thousands of them; a night's cap must clear at least a day.
   */
  static final int BATCH = 5000;

  /** Batches per run per table, so one night's work is bounded even against a backlog. */
  static final int MAX_BATCHES = 200;

  private final FlowRepository repo;
  private final Clock clock;

  /**
   * {@code @Autowired} is LOAD-BEARING: two constructors with none annotated aborts context refresh
   * ({@code SpringComponentConstructorGuardTest} and the writer-enabled smoke test pin it).
   */
  @Autowired
  public FlowRetention(FlowRepository repo) {
    this(repo, Clock.systemUTC());
  }

  FlowRetention(FlowRepository repo, Clock clock) {
    this.repo = repo;
    this.clock = clock;
  }

  /** 03:50 UTC daily — after the darkpool sweep, far from any US session. */
  @Scheduled(cron = "0 50 3 * * *", zone = "UTC")
  public void purge() {
    runOnce();
  }

  /** Returns how many rows went (orders + ticker-days). Never throws. */
  int runOnce() {
    OffsetDateTime now = OffsetDateTime.now(clock);
    LocalDate today = now.atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
    int orders =
        sweepOrders("orders", now.minusDays(ORDER_RETENTION_DAYS), repo::deleteOrdersOlderThan);
    int days =
        sweepDays(
            "ticker_days",
            today.minusDays(TICKER_DAY_RETENTION_DAYS),
            repo::deleteTickerDaysOlderThan);
    return orders + days;
  }

  private static int sweepOrders(
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
      log.error("flow {} retention failed after {} row(s); will retry tomorrow", what, total, e);
      return total;
    }
    if (total > 0) {
      log.info("flow retention removed {} {} before {}", total, what, cutoff);
      if (total >= BATCH * MAX_BATCHES) {
        log.warn(
            "flow {} retention hit its per-run cap ({}); the backlog drains on later nights",
            what,
            BATCH * MAX_BATCHES);
      }
    }
    return total;
  }

  private static int sweepDays(
      String what, LocalDate cutoff, BiFunction<LocalDate, Integer, Integer> delete) {
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
      log.error("flow {} retention failed after {} row(s); will retry tomorrow", what, total, e);
      return total;
    }
    if (total > 0) {
      log.info("flow retention removed {} {} before {}", total, what, cutoff);
      if (total >= BATCH * MAX_BATCHES) {
        log.warn(
            "flow {} retention hit its per-run cap ({}); the backlog drains on later nights",
            what,
            BATCH * MAX_BATCHES);
      }
    }
    return total;
  }
}
