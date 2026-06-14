package com.ohmytradeagent.orchestrator.platform;

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
 * Fast unit slice (no Testcontainers) proving the {@code strategy.config.source} flag selects
 * exactly ONE {@link StrategyRegistry} bean. {@link YamlStrategyRegistry} carries {@code
 * matchIfMissing=true}; {@link DbStrategyRegistry} is {@code havingValue="db"} — they are mutually
 * exclusive, so {@code getBean(StrategyRegistry.class)} never sees a duplicate.
 */
class StrategyRegistrySelectionTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
          .withUserConfiguration(Deps.class)
          .withUserConfiguration(DbStrategyRegistry.class, YamlStrategyRegistry.class);

  @Test
  void dbFlagSelectsDbRegistryAndYamlIsAbsent() {
    runner
        .withPropertyValues("strategy.config.source=db")
        .run(
            context -> {
              assertThat(context).hasSingleBean(StrategyRegistry.class);
              assertThat(context.getBean(StrategyRegistry.class))
                  .isInstanceOf(DbStrategyRegistry.class);
              assertThat(context).doesNotHaveBean(YamlStrategyRegistry.class);
            });
  }

  @Test
  void yamlFlagSelectsYamlRegistry() {
    runner
        .withPropertyValues("strategy.config.source=yaml")
        .run(
            context -> {
              assertThat(context).hasSingleBean(StrategyRegistry.class);
              assertThat(context.getBean(StrategyRegistry.class))
                  .isInstanceOf(YamlStrategyRegistry.class);
              assertThat(context).doesNotHaveBean(DbStrategyRegistry.class);
            });
  }

  @Test
  void flagAbsentDefaultsToYamlRegistry() {
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(StrategyRegistry.class);
          assertThat(context.getBean(StrategyRegistry.class))
              .isInstanceOf(YamlStrategyRegistry.class);
          assertThat(context).doesNotHaveBean(DbStrategyRegistry.class);
        });
  }

  /** Supplies the collaborators {@link DbStrategyRegistry} needs to construct. */
  @Configuration
  static class Deps {
    @Bean
    DSLContext dslContext() {
      return mock(DSLContext.class);
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
