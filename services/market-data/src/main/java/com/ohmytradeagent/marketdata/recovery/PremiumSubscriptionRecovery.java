package com.ohmytradeagent.marketdata.recovery;

import com.ohmytradeagent.contract.SubscribePremiumRequest;
import com.ohmytradeagent.contract.SubscribePremiumResult;
import com.ohmytradeagent.marketdata.MarketHours;
import com.ohmytradeagent.marketdata.activities.SubscribePremiumActivityImpl;
import com.ohmytradeagent.marketdata.alert.DiscordWebhookClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.client.WorkflowStub;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * #776 Phase 2: RTH-gated, bounded-retry recovery of armed premium subscriptions after a
 * market-data restart.
 *
 * <p>The premium subscription registry is in-process, so ANY market-data restart orphans every
 * armed trailing stop: the workflow still reports {@code trailingArmed=true} while no ticks flow.
 * On {@link ApplicationReadyEvent} this component (a) emits the recovery-started marker and (b)
 * starts ONE daemon thread that waits for regular trading hours, then sweeps running
 * PositionWorkflows and re-subscribes every armed trail THROUGH {@link
 * SubscribePremiumActivityImpl#subscribePremium} — the merged (occ, workflowId) dedup + min-move
 * throttle path. Recovery never talks to the provider directly: the shared path is what makes a
 * retry (and a race against an operator's manual re-arm) a logged no-op instead of a duplicate
 * subscription, and what keeps the re-subscribed book from bursting signals into workflows with no
 * continue-as-new.
 *
 * <p><b>Fail-soft is the headline property.</b> Nothing Temporal-facing happens on the boot thread;
 * the loop thread is a daemon with {@code catch (Throwable)} at its top — a Temporal blip, a
 * serialization error, an OOM in the sweep may cost the recovery, never market-data itself.
 *
 * <p><b>Why the RTH gate.</b> Three feed-side guards ({@code lastAcceptedPremium}, {@code
 * lastQuoteStamp}, the throttles) are cold on a fresh process, {@code processTick} has no debounce,
 * and outside RTH Alpaca's {@code latestQuote} returns the PRIOR session's quote (observed
 * 2026-08-20 08:35Z). A one-shot boot sweep outside RTH would re-subscribe the whole armed book
 * onto stale quotes with every filter cold, so outside RTH the loop sleeps until the next open.
 *
 * <p><b>Known residual</b> (deliberate, do not build around it): the RTH check is weekday-only with
 * NO holiday calendar — identical to the equity-gate precedent ({@link MarketHours}). On a market
 * holiday the sweep runs against stale quotes; accepted because it matches the estate's existing
 * gate semantics and the alternative is a holiday-calendar dependency.
 */
@Component
public class PremiumSubscriptionRecovery {

  private static final Logger log = LoggerFactory.getLogger(PremiumSubscriptionRecovery.class);

  /** Read-only visibility listing of the workflows that could carry an armed trail. */
  static final String LIST_QUERY =
      "WorkflowType = 'PositionWorkflow' AND ExecutionStatus = 'Running'";

  /** Injectable wait, so tests drive the gate/backoff with a fake instead of real sleeping. */
  interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }

  private final WorkflowClient workflowClient;
  private final SubscribePremiumActivityImpl subscribeActivity;
  private final DiscordWebhookClient alerts;
  private final Clock clock;
  private final Sleeper sleeper;
  private final int maxAttempts;
  private final Duration retryBackoff;
  private final int maxSubscriptions;

  /**
   * Workflows already re-subscribed by THIS recovery run (#784 review: retry/cap interaction).
   * Without it, a book larger than the per-sweep cap can never finish: on every retry the sweep
   * re-subscribes the same oldest positions (each a Phase-1 dedup REUSE that still counts against
   * the cap), truncates the same tail, and gives up after maxAttempts with the newest trails
   * permanently orphaned. Carried across attempts, cleared per run; a skip here costs ZERO cap.
   */
  private final java.util.Set<String> recoveredThisRun = new java.util.HashSet<>();

  private final Duration sweepDeadline;

  private final MeterRegistry registry;
  private final Counter attemptsTotal;

  /** 1 once a sweep fully succeeded, 0 while pending / after giving up. */
  private final AtomicInteger lastResult = new AtomicInteger(0);

  /** The daemon loop thread; test-visible so tests can await completion. */
  private volatile Thread loopThread;

  @Autowired
  public PremiumSubscriptionRecovery(
      WorkflowClient workflowClient,
      SubscribePremiumActivityImpl subscribeActivity,
      DiscordWebhookClient alerts,
      MeterRegistry registry,
      @Value("${market-data.trail-recovery.max-attempts:5}") int maxAttempts,
      @Value("${market-data.trail-recovery.retry-backoff-seconds:30}") long retryBackoffSeconds,
      @Value("${market-data.trail-recovery.max-subscriptions:20}") int maxSubscriptions,
      @Value("${market-data.trail-recovery.sweep-deadline-seconds:120}")
          long sweepDeadlineSeconds) {
    this(
        workflowClient,
        subscribeActivity,
        alerts,
        registry,
        maxAttempts,
        Duration.ofSeconds(retryBackoffSeconds),
        maxSubscriptions,
        Duration.ofSeconds(sweepDeadlineSeconds),
        Clock.system(MarketHours.ET),
        d -> Thread.sleep(d.toMillis()));
  }

  /** Visible for tests: inject a pinned/mutable clock and a fake sleeper. */
  PremiumSubscriptionRecovery(
      WorkflowClient workflowClient,
      SubscribePremiumActivityImpl subscribeActivity,
      DiscordWebhookClient alerts,
      MeterRegistry registry,
      int maxAttempts,
      Duration retryBackoff,
      int maxSubscriptions,
      Duration sweepDeadline,
      Clock clock,
      Sleeper sleeper) {
    this.workflowClient = workflowClient;
    this.subscribeActivity = subscribeActivity;
    this.alerts = alerts;
    this.registry = registry;
    this.maxAttempts = maxAttempts;
    this.retryBackoff = retryBackoff;
    this.maxSubscriptions = maxSubscriptions;
    this.sweepDeadline = sweepDeadline;
    this.clock = clock;
    this.sleeper = sleeper;
    this.attemptsTotal =
        Counter.builder("omo_trail_recovery_attempts_total")
            .description("Sweep attempts made by the premium-subscription boot recovery")
            .register(registry);
    Gauge.builder("omo_trail_recovery_last_result", lastResult, AtomicInteger::get)
        .description("1 when the last recovery run fully succeeded, 0 while pending or failed")
        .register(registry);
  }

  /**
   * Boot hook. Does exactly two things and returns: emits the recovery-started marker (so its
   * absence in pod logs proves the build isn't deployed) and starts the daemon loop thread. No I/O,
   * nothing Temporal-facing, on the caller thread.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    log.info(
        "AUDIT premium-recovery-started: max_attempts={} retry_backoff={} max_subscriptions={}"
            + " sweep_deadline={} rth_gate=Mon-Fri-09:30-16:00-ET",
        maxAttempts,
        retryBackoff,
        maxSubscriptions,
        sweepDeadline);
    Thread thread = new Thread(this::runLoop, "premium-subscription-recovery");
    thread.setDaemon(true);
    loopThread = thread;
    thread.start();
  }

  /** The gate+retry loop (D2). Runs on the daemon thread; never lets a Throwable escape. */
  void runLoop() {
    try {
      int attempts = 0;
      recoveredThisRun.clear();
      while (true) {
        ZonedDateTime nowEt = ZonedDateTime.ofInstant(clock.instant(), MarketHours.ET);
        if (!MarketHours.isRegularTradingHours(nowEt)) {
          ZonedDateTime open = MarketHours.nextRthOpen(nowEt);
          log.info("premium-recovery outside RTH; sleeping until next open {}", open);
          sleeper.sleep(Duration.between(nowEt, open));
          continue; // calendar waits never consume an attempt (attempts count sweep failures)
        }
        attempts++;
        attemptsTotal.increment();
        boolean ok;
        String detail;
        try {
          Sweep sweep = sweepOnce();
          ok = sweep.complete();
          detail = sweep.tallyLine();
        } catch (Throwable e) {
          // The attempt-level guard: a Temporal listing blip, a serialization error — the attempt
          // failed and is retried; nothing may escape into the loop (or the process).
          log.warn(
              "AUDIT premium-recovery-attempt-failed: attempt={} error={}", attempts, e.toString());
          ok = false;
          detail = "attempt threw: " + e;
        }
        if (ok) {
          lastResult.set(1);
          log.info("AUDIT premium-recovery-complete: attempts={} {}", attempts, detail);
          return;
        }
        if (attempts >= maxAttempts) {
          lastResult.set(0);
          String msg =
              "premium-subscription recovery GAVE UP after "
                  + attempts
                  + " attempts ("
                  + detail
                  + "); armed trails may be orphaned — re-arm from /live.";
          log.error("AUDIT premium-recovery-gave-up: {}", msg);
          alerts.post(msg);
          return;
        }
        sleeper.sleep(retryBackoff);
      }
    } catch (Throwable t) {
      // Fail-soft headline property: recovery may die, market-data may not.
      if (t instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      String msg = "premium-subscription recovery crashed: " + t;
      log.error("AUDIT premium-recovery-crashed: {}", msg, t);
      alerts.post(msg);
    }
  }

  /** One sweep attempt (D3). May throw — the loop treats a thrown attempt as failed. */
  Sweep sweepOnce() {
    List<String> workflowIds;
    try (Stream<WorkflowExecutionMetadata> stream = workflowClient.listExecutions(LIST_QUERY)) {
      workflowIds = new ArrayList<>(stream.map(m -> m.getExecution().getWorkflowId()).toList());
    }
    // Standard visibility returns newest-first with no ORDER BY, so a truncated sweep would
    // otherwise drop exactly the long-lived LEAP trails this feature exists for. Reverse to
    // oldest-first: any truncation drops only the NEWEST workflows.
    Collections.reverse(workflowIds);

    Instant deadline = clock.instant().plus(sweepDeadline);
    Tally tally = new Tally();
    int processed = 0;
    for (String workflowId : workflowIds) {
      if (tally.newSubs >= maxSubscriptions) {
        truncate(tally, "subscription cap " + maxSubscriptions, workflowIds.size() - processed);
        break;
      }
      if (!clock.instant().isBefore(deadline)) {
        truncate(tally, "sweep deadline " + sweepDeadline, workflowIds.size() - processed);
        break;
      }
      recoverOne(workflowId, tally);
      processed++;
    }

    Sweep sweep =
        new Sweep(
            tally.recovered,
            tally.failed,
            tally.skippedUnarmed,
            tally.skippedClosed,
            tally.skippedExpired,
            tally.truncated);
    log.info("AUDIT premium-recovery-sweep: {}", sweep.tallyLine());
    tallyOutcomes(sweep);
    return sweep;
  }

  /**
   * One workflow (D3.2–D3.6). Any per-workflow exception tallies {@code failed} and the sweep
   * continues to the next workflow.
   */
  private void recoverOne(String workflowId, Tally tally) {
    if (recoveredThisRun.contains(workflowId)) {
      // Recovered by an earlier attempt of THIS run: still armed, still listed, but its
      // subscription is live. Skipping costs zero cap, which is what lets a >cap book converge.
      tally.recovered++;
      return;
    }
    try {
      WorkflowStub stub = workflowClient.newUntypedWorkflowStub(workflowId);
      // One query covers BOTH trail kinds: `trailingArmed` (chandelier/operator) AND `armed` (the
      // watchlist exit).
      ExitProximityViewMirror proximity =
          stub.query("exitProximity", ExitProximityViewMirror.class);
      if (proximity == null || (!proximity.trailingArmed() && !proximity.armed())) {
        tally.skippedUnarmed++;
        return;
      }
      PositionStateViewMirror state = stub.query("positionState", PositionStateViewMirror.class);
      if (state == null || state.remainingQty() <= 0) {
        tally.skippedClosed++;
        return;
      }
      LocalDate expiry = parseExpiry(proximity.contractSymbol());
      if (expiry != null && expiry.isBefore(LocalDate.ofInstant(clock.instant(), MarketHours.ET))) {
        tally.skippedExpired++;
        return;
      }
      String[] tenantStrategy = parseTenantStrategy(workflowId);
      if (tenantStrategy == null) {
        tally.failed++;
        log.warn("AUDIT premium-recovery-unparseable-workflow-id: wf={}", workflowId);
        return;
      }
      SubscribePremiumRequest request = new SubscribePremiumRequest();
      request.setSchemaVersion(1L);
      request.setTenantId(tenantStrategy[0]);
      request.setStrategyId(tenantStrategy[1]);
      request.setContractSymbol(proximity.contractSymbol());
      request.setPositionWorkflowId(workflowId);
      // THE shared path (non-negotiable): the Phase 1 (occ, workflowId) dedup — no race against an
      // operator's manual re-arm, no throttle-baseline reset — and the min-move throttle both live
      // inside subscribePremium. Recovery must never talk to MarketDataProvider directly.
      SubscribePremiumResult result = subscribeActivity.subscribePremium(request);
      if (result != null && result.getStatus() == SubscribePremiumResult.Status.SUBSCRIBED) {
        tally.recovered++;
        tally.newSubs++;
        recoveredThisRun.add(workflowId);
      } else {
        // FAILED is the one case the tally exists to catch — never skipped_unarmed.
        tally.failed++;
        log.warn(
            "AUDIT premium-recovery-subscribe-failed: wf={} occ={} error={}",
            workflowId,
            proximity.contractSymbol(),
            result == null ? "null result" : result.getError());
      }
    } catch (RuntimeException e) {
      tally.failed++;
      log.warn("AUDIT premium-recovery-workflow-failed: wf={} error={}", workflowId, e.toString());
    }
  }

  private void truncate(Tally tally, String reason, int remaining) {
    tally.truncated++;
    String msg =
        "premium-recovery sweep TRUNCATED ("
            + reason
            + "): remaining="
            + remaining
            + " workflows untouched; attempt is partial and will retry";
    log.error("AUDIT premium-recovery-truncated: {}", msg);
    alerts.post(msg);
  }

  private void tallyOutcomes(Sweep sweep) {
    outcome("recovered", sweep.recovered());
    outcome("failed", sweep.failed());
    outcome("skipped_unarmed", sweep.skippedUnarmed());
    outcome("skipped_closed", sweep.skippedClosed());
    outcome("skipped_expired", sweep.skippedExpired());
    outcome("truncated", sweep.truncated());
  }

  private void outcome(String outcome, int count) {
    if (count > 0) {
      registry.counter("omo_trail_recovery_outcome_total", "outcome", outcome).increment(count);
    }
  }

  /** Mutable per-sweep counters; published as an immutable {@link Sweep} at end of attempt. */
  private static final class Tally {
    int recovered;

    /**
     * NEW provider subscriptions this sweep — the cap gates on THIS, never on carried-over skips.
     */
    int newSubs;

    int failed;
    int skippedUnarmed;
    int skippedClosed;
    int skippedExpired;
    int truncated;
  }

  Thread loopThreadForTest() {
    return loopThread;
  }

  /** End-of-attempt tally (D3.8). {@code truncated} counts sweeps cut short, not workflows. */
  record Sweep(
      int recovered,
      int failed,
      int skippedUnarmed,
      int skippedClosed,
      int skippedExpired,
      int truncated) {

    boolean complete() {
      return failed == 0 && truncated == 0;
    }

    String tallyLine() {
      return "recovered=%d failed=%d skipped_unarmed=%d skipped_closed=%d skipped_expired=%d truncated=%d"
          .formatted(recovered, failed, skippedUnarmed, skippedClosed, skippedExpired, truncated);
    }
  }

  /**
   * Byte-for-byte port of the BFF's {@code PositionsReader.parseExpiry}: strip spaces first so BOTH
   * the padded canonical form ({@code TSLA 260918P00300000}) and the compact broker form ({@code
   * TSLA260918P00300000}) parse; {@code YYMMDD} at {@code compact.length()-15}; null on any parse
   * failure = fail-OPEN, matching the BFF (a parse quirk must never hide a real armed trail from
   * recovery).
   */
  static LocalDate parseExpiry(String occ) {
    if (occ == null) {
      return null;
    }
    String compact = occ.replace(" ", "");
    if (compact.length() < 15) {
      return null;
    }
    String yymmdd = compact.substring(compact.length() - 15, compact.length() - 9);
    try {
      int yy = Integer.parseInt(yymmdd.substring(0, 2));
      int mm = Integer.parseInt(yymmdd.substring(2, 4));
      int dd = Integer.parseInt(yymmdd.substring(4, 6));
      return LocalDate.of(2000 + yy, mm, dd);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Parses {@code {tenant, strategy}} from a canonical {@code t-{tenant}/s-{strategy}/…} workflow
   * id ({@code WorkflowIds.tenantStrategy} shape). Null when the id does not match — the caller
   * tallies {@code failed} and continues.
   */
  static String[] parseTenantStrategy(String workflowId) {
    if (workflowId == null || !workflowId.startsWith("t-")) {
      return null;
    }
    int s = workflowId.indexOf("/s-");
    if (s < 0) {
      return null;
    }
    String tenant = workflowId.substring(2, s);
    int end = workflowId.indexOf('/', s + 3);
    String strategy = end < 0 ? workflowId.substring(s + 3) : workflowId.substring(s + 3, end);
    if (tenant.isEmpty() || strategy.isEmpty()) {
      return null;
    }
    return new String[] {tenant, strategy};
  }
}
