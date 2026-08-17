package com.ohmytradeagent.orchestrator.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The two ConfigMap-fed seed reconcilers exist ONLY in yaml-mode.
 *
 * <p>Both read the mounted {@code tenants/} tree and {@code INSERT ... ON CONFLICT DO NOTHING} to
 * warm the DB store before an operator flips to db-mode. That cutover is long done: on the live
 * cluster both logged {@code seeded 0} on every boot, and no {@code strategy_config} row was ever
 * written by {@code seed:boot}. Keeping them alive in db-mode is what tied the orchestrator to a
 * mounted ConfigMap that no longer reflects reality
 * (docs/plans/PLAN-2026-08-17-retire-tenants-configmap.md, Phase 3).
 *
 * <p>Mirrors {@code StrategyRegistrySelectionTest}: a fast context slice, no Testcontainers.
 *
 * <p>NOTE ON VACUOUS PASSES: asserting only "the context started" would pass whether or not the
 * condition exists, which is how a gate nobody watched fail ships broken. Every case here asserts
 * bean PRESENCE or ABSENCE explicitly, and deleting either {@code @ConditionalOnProperty} makes the
 * db-mode cases fail.
 */
class SeedReconcilerSelectionTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
          .withUserConfiguration(
              Deps.class, StrategyConfigSeedReconciler.class, TenantConfigSeedReconciler.class);

  @Test
  void dbModeConstructsNeitherSeeder() {
    runner
        .withPropertyValues("strategy.config.source=db", "tenant.config.source=db")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(StrategyConfigSeedReconciler.class);
              assertThat(context).doesNotHaveBean(TenantConfigSeedReconciler.class);
            });
  }

  /**
   * The two flags are independent — a half-migrated cluster keeps only the seeder it still needs.
   */
  @Test
  void flagsAreIndependent() {
    runner
        .withPropertyValues("strategy.config.source=db", "tenant.config.source=yaml")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(StrategyConfigSeedReconciler.class);
              assertThat(context).hasSingleBean(TenantConfigSeedReconciler.class);
            });
  }

  @Test
  void yamlModeConstructsBothSeeders() {
    runner
        .withPropertyValues("strategy.config.source=yaml", "tenant.config.source=yaml")
        .run(
            context -> {
              assertThat(context).hasSingleBean(StrategyConfigSeedReconciler.class);
              assertThat(context).hasSingleBean(TenantConfigSeedReconciler.class);
            });
  }

  /** Flags absent (local dev, tests) must keep the seeders — matchIfMissing=true. */
  @Test
  void flagsAbsentConstructBothSeeders() {
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(StrategyConfigSeedReconciler.class);
          assertThat(context).hasSingleBean(TenantConfigSeedReconciler.class);
        });
  }

  @Configuration
  static class Deps {
    @Bean
    DSLContext dsl() {
      return mock(DSLContext.class);
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
