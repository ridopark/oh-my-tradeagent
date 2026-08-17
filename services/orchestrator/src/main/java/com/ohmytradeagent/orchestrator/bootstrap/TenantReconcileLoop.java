package com.ohmytradeagent.orchestrator.bootstrap;

import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import com.ohmytradeagent.orchestrator.platform.TenantStrategy;
import io.temporal.client.schedules.ScheduleClient;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Restart-free tenant onboarding. On a fixed delay, enumerates {@code (tenant, strategy)} pairs
 * from the active {@link StrategyRegistry} (the DB {@code SELECT DISTINCT} set in db-mode, the
 * mounted {@code tenants/} scan in yaml-mode) and ensures each one has its per-tenant kill-switch
 * and reconciliation schedule — by invoking the SAME idempotent {@code ensureForTenantStrategy}
 * logic the boot {@link org.springframework.boot.ApplicationRunner}s use.
 *
 * <p>This makes a UI-written {@code strategy_config} row (Phase I) pick up within one tick, with no
 * orchestrator restart. Phase A is <b>additive only</b>: it never tears down a kill-switch or
 * schedule (tenant deactivation is Phase F).
 *
 * <p>Because the underlying broker/Temporal calls are idempotent ({@code REJECT_DUPLICATE} /
 * swallowed {@code AlreadyRunning}), re-asserting every tick is safe — but to avoid per-tick log
 * spam we keep an in-memory {@code seen} set and only call the ensure logic for pairs not yet seen.
 * A re-ensure of an already-seen pair is still a benign no-op.
 *
 * <p><b>Not workflow code.</b> A Spring {@code @Scheduled} bean lives entirely outside Temporal
 * workflow history, so there is NO {@code Workflow.getVersion} marker here — replay determinism
 * does not apply (each tick is a fresh ordinary method invocation).
 *
 * <p><b>Also an {@link ApplicationRunner}: one pass runs at STARTUP.</b> The scheduled tick's first
 * firing is a full {@code fixed-delay-ms} (default 60s) after boot, so for that first minute the
 * only kill-switch coverage was whatever the boot bootstrappers scanned out of the mounted {@code
 * tenants/} tree. On the live cluster that tree is a stale SUBSET of the DB — 4 of 8 {@code
 * (tenant, strategy)} pairs on 2026-08-17, with {@code prod-jinchul} and {@code paper_jinchiul}
 * absent from it entirely — so those tenants had no kill switch until the first tick. Running one
 * pass at startup makes this loop the coverage floor rather than a late top-up, which is what lets
 * the mounted tree be retired (docs/plans/PLAN-2026-08-17-retire-tenants-configmap.md, Phase 1).
 */
@Component
@Profile("!test")
@ConditionalOnProperty(
    name = "orchestrator.tenant-reconcile.enabled",
    havingValue = "true",
    matchIfMissing = true)
@Order(Ordered.LOWEST_PRECEDENCE)
public class TenantReconcileLoop implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(TenantReconcileLoop.class);

  private final StrategyRegistry registry;
  private final KillSwitchBootstrapper killSwitchBootstrapper;
  private final ReconciliationScheduleBootstrapper reconciliationScheduleBootstrapper;

  /**
   * Pairs already successfully ensured this process lifetime; re-ensuring is a benign idempotent
   * no-op. Concurrent-safe via {@link ConcurrentHashMap#newKeySet()}: default Spring {@code
   * fixedDelay} scheduling already serializes ticks on a single thread, but relying on that is an
   * implicit platform guarantee — if a custom multi-threaded {@code TaskScheduler} ever runs ticks
   * in parallel, a plain {@code LinkedHashSet} would be a silent data race. With a concurrent set
   * the worst case is a pair ensured twice (idempotent), never set corruption. We make the
   * thread-safety invariant explicit rather than implicit.
   */
  private final Set<TenantStrategy> seen = ConcurrentHashMap.newKeySet();

  /**
   * Serializes passes across the TWO entry points. Until the startup pass existed, ticks were
   * serialized only because Spring's default {@code fixedDelay} scheduler is single-threaded — an
   * implicit guarantee that does NOT extend to {@link #run}, which executes on the main thread
   * during boot. Without this lock a slow startup pass could overlap the first scheduled tick.
   *
   * <p>{@code tryLock} rather than {@code lock}: the default Spring scheduler pool holds ONE
   * thread, so blocking it would stall every other {@code @Scheduled} task in the process. A pass
   * that cannot get the lock simply returns — the work is idempotent and re-run every tick, so
   * skipping costs at most one interval.
   */
  private final ReentrantLock passLock = new ReentrantLock();

  public TenantReconcileLoop(
      StrategyRegistry registry,
      KillSwitchBootstrapper killSwitchBootstrapper,
      ReconciliationScheduleBootstrapper reconciliationScheduleBootstrapper) {
    this.registry = registry;
    this.killSwitchBootstrapper = killSwitchBootstrapper;
    this.reconciliationScheduleBootstrapper = reconciliationScheduleBootstrapper;
  }

  /**
   * Startup pass. Runs on the main thread during boot, so every {@code (tenant, strategy)} the
   * registry knows about has its kill-switch and reconciliation schedule ensured before the process
   * starts serving — not a minute later.
   *
   * <p>Ordered {@link Ordered#LOWEST_PRECEDENCE} so the existing boot bootstrappers go first; the
   * ensures are idempotent ({@code REJECT_DUPLICATE}) so overlapping with them is a no-op either
   * way, and this only expresses that intent.
   *
   * <p>Cannot wedge boot: {@link #reconcileOnce} catches per-pair failures and swallows a failing
   * {@code registry.list()}, leaving the pair unseen and retried on the next tick.
   */
  @Override
  public void run(ApplicationArguments args) {
    log.info("tenant reconcile: startup pass");
    reconcileTick();
  }

  /**
   * One reconcile pass on the fixed delay. {@code initialDelay} mirrors the boot bootstrappers'
   * settle window; {@link #run} now covers the interval before the first firing.
   */
  @Scheduled(
      fixedDelayString = "${orchestrator.tenant-reconcile.fixed-delay-ms:60000}",
      initialDelayString = "${orchestrator.tenant-reconcile.fixed-delay-ms:60000}")
  public void reconcileTick() {
    if (!passLock.tryLock()) {
      // Another pass (startup or a previous tick) is mid-flight. Skipping is safe and preferable
      // to blocking the single-threaded scheduler pool: the work is idempotent and re-run next
      // tick, and the in-flight pass is already doing it.
      log.debug("tenant reconcile: a pass is already in flight; skipping this one");
      return;
    }
    try {
      reconcileOnce();
    } finally {
      passLock.unlock();
    }
  }

  private void reconcileOnce() {
    List<TenantStrategy> desired;
    try {
      desired = registry.list();
    } catch (RuntimeException e) {
      log.error("tenant reconcile: registry.list() failed; skipping this tick", e);
      return;
    }

    ScheduleClient scheduleClient = null;
    int ensured = 0;
    for (TenantStrategy ts : desired) {
      if (seen.contains(ts)) {
        continue;
      }
      // A pair is marked seen ONLY once both ensures report success, so a transient failure
      // (e.g. Temporal briefly unreachable) is retried on the next tick instead of latched as
      // done — otherwise a live tenant could be left with no kill-switch until a restart. The
      // per-pair try/catch keeps an unexpected throw (e.g. newScheduleClient) from aborting the
      // tick or starving later pairs; an un-added pair is simply retried next tick.
      try {
        if (scheduleClient == null) {
          // Lazily build the ScheduleClient only when there is at least one new pair to ensure.
          scheduleClient = reconciliationScheduleBootstrapper.newScheduleClient();
        }
        boolean killSwitchOk =
            killSwitchBootstrapper.ensureForTenantStrategy(ts.tenantId(), ts.strategyId());
        boolean reconOk =
            reconciliationScheduleBootstrapper.ensureForTenantStrategy(
                scheduleClient, ts.tenantId(), ts.strategyId());
        if (killSwitchOk && reconOk) {
          seen.add(ts);
          ensured++;
          log.info(
              "tenant reconcile: ensured kill-switch + recon schedule for tenant={} strategy={}",
              ts.tenantId(),
              ts.strategyId());
        } else {
          log.warn(
              "tenant reconcile: tenant={} strategy={} not fully ensured"
                  + " (kill_switch_ok={} recon_ok={}); will retry next tick",
              ts.tenantId(),
              ts.strategyId(),
              killSwitchOk,
              reconOk);
        }
      } catch (RuntimeException e) {
        log.error(
            "tenant reconcile: unexpected failure ensuring tenant={} strategy={}; will retry next tick",
            ts.tenantId(),
            ts.strategyId(),
            e);
      }
    }
    if (ensured > 0) {
      log.info("tenant reconcile: ensured {} new (tenant, strategy) pair(s) this tick", ensured);
    }
  }
}
