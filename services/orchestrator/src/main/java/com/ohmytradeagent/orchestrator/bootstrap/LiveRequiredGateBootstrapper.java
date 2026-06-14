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
 * Phase P2 live-safety: on Spring start, fails fast when a {@code -live} strategy is missing a
 * required loss gate (daily-loss threshold or notional cap). Ordered {@link
 * Ordered#HIGHEST_PRECEDENCE} so it runs before the {@link KillSwitchBootstrapper} / {@link
 * ReconciliationScheduleBootstrapper} start any workflows — an unsafe real-money config never
 * reaches the trading path.
 */
@Component
@Profile("!test")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LiveRequiredGateBootstrapper implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(LiveRequiredGateBootstrapper.class);

  private final Path tenantsDir;

  public LiveRequiredGateBootstrapper(
      @Value("${orchestrator.tenants-dir:tenants}") String tenantsDir) {
    this.tenantsDir = Path.of(tenantsDir);
  }

  @Override
  public void run(ApplicationArguments args) {
    LiveRequiredGateValidator.validate(tenantsDir);
    log.info("live required-gate invariant validated for tenants dir {}", tenantsDir);
  }
}
