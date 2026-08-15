package com.ohmytradeagent.tdbff.optionschat;

import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly retention for the /options-chat mirror (Phase 6).
 *
 * <p>Until this shipped, nothing ever deleted from the store: messages accumulated forever and,
 * since media mirroring, so did attachment BYTEA. It was the only part of the feature with no
 * ceiling, on a node whose single volume also backs the trading databases and cannot be expanded
 * online.
 *
 * <p>A {@code @Scheduled} bean rather than a CronJob calling an endpoint. The BFF already owns this
 * schema and holds the only writer DSL, and it runs a single replica — so a job needs no new image,
 * manifest, HTTP surface or credential, and no leader election. The audit-completeness CronJob
 * earns its separate pod because it runs a different image against a different database; this does
 * not.
 *
 * <p>THE DANGEROUS FAILURE HERE IS NOT FAILING TO DELETE — it is deleting everything. A retention
 * of 0 (unset property, a typo, a bad ConfigMap) computes a cutoff of "now" and would sweep the
 * entire mirror on the first tick. So a non-positive retention DISABLES the job rather than being
 * treated as "keep nothing", and the floor below refuses anything under a day.
 */
@Component
@ConditionalOnProperty(
    name = {"options-chat.enabled", "dashboard.writer.enabled"},
    havingValue = "true")
public class OptionsChatRetention {

  private static final Logger log = LoggerFactory.getLogger(OptionsChatRetention.class);

  /** Rows per statement. Small enough that no single delete takes a long lock. */
  static final int BATCH = 500;

  /** Batches per run, so one night's work is bounded even against a huge backlog. */
  static final int MAX_BATCHES = 200;

  /**
   * Refuse to treat a sub-day retention as real config. Deleting everything posted more than a few
   * hours ago is far more likely to be a mistake than an intent, and it is unrecoverable.
   */
  static final int MIN_RETENTION_DAYS = 1;

  private final OptionsChatRepository repo;
  private final int retentionDays;
  private final Clock clock;

  /**
   * {@code @Autowired} is LOAD-BEARING, not decoration. This class declares two constructors (the
   * one below takes an injected Clock), and with more than one candidate and none annotated, Spring
   * stops choosing: it falls back to a no-arg constructor, finds none, and aborts context refresh
   * with {@code NoSuchMethodException: <init>()}. Since this bean only exists when both
   * options-chat.enabled and dashboard.writer.enabled are true — the cluster's configuration — the
   * failure lands nowhere except production, where it CrashLoopBackOffs the BFF and takes /live and
   * /config down with it. PR #486 did precisely this; {@code
   * ApplicationContextWriterEnabledSmokeTest} is the guard that now catches it.
   */
  @Autowired
  public OptionsChatRetention(
      OptionsChatRepository repo,
      @Value("${options-chat.retention-days:30}") String retentionDays) {
    this(repo, parseRetentionDays(retentionDays), Clock.systemUTC());
  }

  /**
   * A value that is not a number DISABLES the sweep instead of refusing to start.
   *
   * <p>The {@code :30} default only covers an ABSENT property. An env var that is present and blank
   * — {@code OPTIONS_CHAT_RETENTION_DAYS: ""}, the ordinary way to clear a ConfigMap entry —
   * resolves to the empty string, and binding that straight to an {@code int} throws during context
   * refresh. That would make the one misconfiguration this class does not already tolerate the one
   * that takes the whole BFF down, when the contract above is that a bad ConfigMap is inert.
   * Non-positive is already handled downstream, so returning 0 routes a junk value into the same
   * loud, safe path.
   */
  private static int parseRetentionDays(String raw) {
    try {
      return Integer.parseInt(raw.trim());
    } catch (RuntimeException e) {
      log.error(
          "options-chat retention-days is not a number ({}) — DISABLING the sweep; the mirror will"
              + " grow without bound until this is corrected",
          raw);
      return 0;
    }
  }

  OptionsChatRetention(OptionsChatRepository repo, int retentionDays, Clock clock) {
    this.repo = repo;
    this.retentionDays = retentionDays;
    this.clock = clock;
  }

  /**
   * 03:30 UTC daily — after the 02:00 audit-completeness verifier, so a heavy first run cannot slow
   * the job whose green history is an operator gate, and far from any US market session.
   */
  @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
  public void purge() {
    runOnce();
  }

  /** Returns how many messages were removed. Never throws — a retention failure must not crash. */
  int runOnce() {
    if (retentionDays < MIN_RETENTION_DAYS) {
      // Loud, because silently retaining forever is how a disk fills.
      log.warn(
          "options-chat retention is DISABLED (retention-days={} is below the {}-day floor) — "
              + "the mirror will grow without bound",
          retentionDays,
          MIN_RETENTION_DAYS);
      return 0;
    }

    OffsetDateTime cutoff = OffsetDateTime.now(clock).minusDays(retentionDays);
    int total = 0;
    try {
      for (int i = 0; i < MAX_BATCHES; i++) {
        int deleted = repo.deleteOlderThan(cutoff, BATCH);
        total += deleted;
        if (deleted < BATCH) {
          break;
        }
      }
    } catch (Exception e) { // noqa - a retention failure is not worth taking the BFF down for
      log.error("options-chat retention failed after {} message(s); will retry tomorrow", total, e);
      return total;
    }

    if (total > 0) {
      // Worth stating: this reclaims space for REUSE by the table, but does not return it to the
      // filesystem — the file stops growing, it does not shrink (that needs a VACUUM FULL).
      log.info(
          "options-chat retention removed {} message(s) posted before {} (attachments cascaded)",
          total,
          cutoff);
      if (total >= BATCH * MAX_BATCHES) {
        log.warn(
            "options-chat retention hit its per-run cap ({}); a backlog remains and will drain on"
                + " subsequent nights",
            BATCH * MAX_BATCHES);
      }
    }
    return total;
  }
}
