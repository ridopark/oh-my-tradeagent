package com.ohmytradeagent.orchestrator.bootstrap;

import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import com.ohmytradeagent.orchestrator.platform.TenantStrategy;
import io.temporal.client.schedules.ScheduleClient;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
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
 */
@Component
@Profile("!test")
@ConditionalOnProperty(
    name = "orchestrator.tenant-reconcile.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class TenantReconcileLoop {

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

  public TenantReconcileLoop(
      StrategyRegistry registry,
      KillSwitchBootstrapper killSwitchBootstrapper,
      ReconciliationScheduleBootstrapper reconciliationScheduleBootstrapper) {
    this.registry = registry;
    this.killSwitchBootstrapper = killSwitchBootstrapper;
    this.reconciliationScheduleBootstrapper = reconciliationScheduleBootstrapper;
  }

  /**
   * One reconcile pass. {@code initialDelay} mirrors the boot bootstrappers' settle window so the
   * first tick runs after they have had a chance to start their workflows; the boot path remains
   * the primary one and this loop is the restart-free superset.
   */
  @Scheduled(
      fixedDelayString = "${orchestrator.tenant-reconcile.fixed-delay-ms:60000}",
      initialDelayString = "${orchestrator.tenant-reconcile.fixed-delay-ms:60000}")
  public void reconcileTick() {
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
