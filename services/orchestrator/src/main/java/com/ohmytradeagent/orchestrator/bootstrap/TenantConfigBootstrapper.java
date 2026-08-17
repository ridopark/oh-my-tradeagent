package com.ohmytradeagent.orchestrator.bootstrap;

import com.ohmytradeagent.orchestrator.platform.TenantRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Live-safety boot gate for tenant-level config. On Spring start it LOADS every tenant's {@code
 * tenant.yaml} through the {@link TenantRegistry} so an out-of-range {@code account_daily_loss_pct}
 * fails the boot LOUDLY and EARLY — before any kill-switch workflow starts trading — instead of
 * silently disabling the account cap on real money or surfacing only as a confusing per-heartbeat
 * audit error later.
 *
 * <p>The actual bound check lives in {@link
 * com.ohmytradeagent.orchestrator.platform.TenantConfig#setAccountDailyLossPct} (rejects anything
 * outside {@code (0,1]} during Jackson parse). This bootstrapper just guarantees that parse runs at
 * startup for every tenant; the throw propagates and boot fails closed — mirroring {@link
 * LiveRequiredGateBootstrapper} for strategy config.
 *
 * <p>Enumeration comes from {@link TenantRegistry#list()} — tenant-scoped, NOT derived from {@code
 * StrategyRegistry.list()}: the strategy enumeration only emits tenants that have at least one
 * strategy, so a tenant whose config carries the cap but has no strategies yet would be skipped and
 * its bad value never parsed. The config gate must cover every tenant it validates.
 *
 * <p>Previously this walked {@code tenants/<tenant>/} on disk. That tied the gate to a mounted
 * ConfigMap which on the live cluster held a stale SUBSET of the tenants (3 of 5 on 2026-08-17), so
 * two live tenants were never gated at all; it also counted Kubernetes atomic-write artifacts
 * ({@code ..data}, {@code ..2026_08_17_07_34_10.NNN}) as tenants. Going through the registry
 * validates exactly the tenants the live read path serves, in both yaml and db mode.
 *
 * <p>Ordered {@link Ordered#HIGHEST_PRECEDENCE}{@code + 11} so it runs alongside the other
 * live-safety gate and before the default-order {@link KillSwitchBootstrapper} starts any workflow.
 */
@Component
@Profile("!test")
@Order(Ordered.HIGHEST_PRECEDENCE + 11)
public class TenantConfigBootstrapper implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(TenantConfigBootstrapper.class);

  private final TenantRegistry tenantRegistry;

  public TenantConfigBootstrapper(TenantRegistry tenantRegistry) {
    this.tenantRegistry = tenantRegistry;
  }

  @Override
  public void run(ApplicationArguments args) {
    List<String> tenantIds = tenantRegistry.list();
    for (String tenantId : tenantIds) {
      // Load through the registry: a bad account_daily_loss_pct throws here (setter rejects it),
      // and the throw propagates so boot fails closed rather than trading with a neutered cap.
      tenantRegistry.get(tenantId);
    }
    log.info("tenant-config invariant validated for {} tenant(s)", tenantIds.size());
  }
}
