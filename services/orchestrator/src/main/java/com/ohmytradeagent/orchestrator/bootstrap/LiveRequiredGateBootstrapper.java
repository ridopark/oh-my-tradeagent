package com.ohmytradeagent.orchestrator.bootstrap;

import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
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
 * Ordered#HIGHEST_PRECEDENCE}{@code + 10} so it runs AFTER the {@link StrategyConfigSeedReconciler}
 * (which back-fills the DB store at {@code HIGHEST_PRECEDENCE}) but still before the default-order
 * {@link KillSwitchBootstrapper} / {@link ReconciliationScheduleBootstrapper} start any workflows —
 * an unsafe real-money config never reaches the trading path.
 *
 * <p>P0c-b2: validates config via the active {@link StrategyRegistry} (always present in non-test
 * profiles — yaml or db), so the boot gate sees exactly the config the live read path will serve.
 */
@Component
@Profile("!test")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class LiveRequiredGateBootstrapper implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(LiveRequiredGateBootstrapper.class);

  private final Path tenantsDir;
  private final StrategyRegistry registry;

  public LiveRequiredGateBootstrapper(
      @Value("${orchestrator.tenants-dir:tenants}") String tenantsDir, StrategyRegistry registry) {
    this.tenantsDir = Path.of(tenantsDir);
    this.registry = registry;
  }

  @Override
  public void run(ApplicationArguments args) {
    LiveRequiredGateValidator.validate(tenantsDir, registry);
    log.info("live required-gate invariant validated for tenants dir {}", tenantsDir);
  }
}
