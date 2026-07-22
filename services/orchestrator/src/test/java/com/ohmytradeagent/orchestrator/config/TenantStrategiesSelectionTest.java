package com.ohmytradeagent.orchestrator.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.ohmytradeagent.orchestrator.activities.DailyPnlActivities;
import com.ohmytradeagent.orchestrator.activities.DbTenantStrategies;
import com.ohmytradeagent.orchestrator.activities.ScannerTenantStrategies;
import com.ohmytradeagent.orchestrator.activities.TenantStrategies;
import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import com.ohmytradeagent.orchestrator.platform.TenantRegistry;
import io.temporal.client.WorkflowClient;
import java.time.Clock;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PLAN-2026-07-22: proves {@code strategy.config.source} selects exactly ONE {@link
 * TenantStrategies} resolver for the account cap — {@link DbTenantStrategies} (DB {@code
 * strategy_config}) in live (db) mode, {@link ScannerTenantStrategies} (the {@code tenants/} tree)
 * for dev/tests — and that the whole {@link AccountKillSwitchConfig} wires under both, so all four
 * cap consumers share the single active resolver. Fast slice (no Testcontainers); collaborators are
 * mocked.
 */
class TenantStrategiesSelectionTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
          .withUserConfiguration(Deps.class, AccountKillSwitchConfig.class);

  @Test
  void dbFlagSelectsDbTenantStrategies() {
    runner
        .withPropertyValues("strategy.config.source=db")
        .run(
            context -> {
              assertThat(context).hasSingleBean(TenantStrategies.class);
              assertThat(context.getBean(TenantStrategies.class))
                  .isInstanceOf(DbTenantStrategies.class);
            });
  }

  @Test
  void yamlFlagSelectsScannerTenantStrategies() {
    runner
        .withPropertyValues("strategy.config.source=yaml")
        .run(
            context -> {
              assertThat(context).hasSingleBean(TenantStrategies.class);
              assertThat(context.getBean(TenantStrategies.class))
                  .isInstanceOf(ScannerTenantStrategies.class);
            });
  }

  @Test
  void flagAbsentDefaultsToScannerTenantStrategies() {
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(TenantStrategies.class);
          assertThat(context.getBean(TenantStrategies.class))
              .isInstanceOf(ScannerTenantStrategies.class);
        });
  }

  /** Collaborators the AccountKillSwitchConfig @Bean methods need to construct. */
  @Configuration
  static class Deps {
    @Bean
    TenantRegistry tenantRegistry() {
      return mock(TenantRegistry.class);
    }

    @Bean
    StrategyRegistry strategyRegistry() {
      return mock(StrategyRegistry.class);
    }

    @Bean
    DailyPnlActivities dailyPnlActivities() {
      return mock(DailyPnlActivities.class);
    }

    @Bean
    WorkflowClient workflowClient() {
      return mock(WorkflowClient.class);
    }

    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }

    @Bean
    DSLContext dslContext() {
      return mock(DSLContext.class);
    }
  }
}
