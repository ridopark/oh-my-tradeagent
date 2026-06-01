package com.ohmytradeagent.orchestrator.bootstrap;

import java.nio.file.Path;
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
 * broker_target}. Ordered {@link Ordered#HIGHEST_PRECEDENCE} so it runs before the {@link
 * KillSwitchBootstrapper} / {@link ReconciliationScheduleBootstrapper} start any workflows — a
 * violating tenants tree never reaches the trading path.
 */
@Component
@Profile("!test")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CrossTenantBrokerTargetBootstrapper implements ApplicationRunner {

  private static final Logger log =
      LoggerFactory.getLogger(CrossTenantBrokerTargetBootstrapper.class);

  private final Path tenantsDir;

  public CrossTenantBrokerTargetBootstrapper(
      @Value("${orchestrator.tenants-dir:tenants}") String tenantsDir) {
    this.tenantsDir = Path.of(tenantsDir);
  }

  @Override
  public void run(ApplicationArguments args) {
    CrossTenantBrokerTargetValidator.validate(tenantsDir);
    log.info("cross-tenant broker_target invariant validated for tenants dir {}", tenantsDir);
  }
}
