package com.ohmytradeagent.orchestrator.bootstrap;

import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Issue #323 part (b): on Spring start, fails fast when two distinct tenants map to the same {@code
 * broker_target}. Ordered {@link Ordered#HIGHEST_PRECEDENCE}{@code + 10} so it runs AFTER the
 * {@link StrategyConfigSeedReconciler} (which back-fills the DB store at {@code
 * HIGHEST_PRECEDENCE}) but still before the default-order {@link KillSwitchBootstrapper} / {@link
 * ReconciliationScheduleBootstrapper} start any workflows — a violating tenants tree never reaches
 * the trading path.
 *
 * <p>P0c-b2: broker_target is resolved via the active {@link StrategyRegistry} (always present in
 * non-test profiles — yaml or db).
 */
@Component
@Profile("!test")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CrossTenantBrokerTargetBootstrapper implements ApplicationRunner {

  private static final Logger log =
      LoggerFactory.getLogger(CrossTenantBrokerTargetBootstrapper.class);

  private final StrategyRegistry registry;
  private final boolean sharedBrokerAccounts;

  public CrossTenantBrokerTargetBootstrapper(
      @Value("${multitenant.broker-accounts.enabled:false}") boolean sharedBrokerAccounts,
      StrategyRegistry registry) {
    this.sharedBrokerAccounts = sharedBrokerAccounts;
    this.registry = registry;
  }

  @Override
  public void run(ApplicationArguments args) {
    // sharedBrokerAccounts (multitenant.broker-accounts.enabled) is DARK by default: the strict
    // #323
    // one-tenant-per-broker_target rule holds until P4-c-b makes the runtime account reads
    // per-tenant.
    CrossTenantBrokerTargetValidator.validate(registry, sharedBrokerAccounts);
    log.info(
        "cross-tenant broker_target invariant validated via the active strategy registry"
            + " (sharedBrokerAccounts={})",
        sharedBrokerAccounts);
  }
}
