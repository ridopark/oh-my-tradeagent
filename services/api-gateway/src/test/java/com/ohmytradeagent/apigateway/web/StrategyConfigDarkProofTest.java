package com.ohmytradeagent.apigateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * UI-P3-b dark proof. With {@code strategy.config.write.enabled} UNSET (the repo default), the
 * {@link StrategyConfigController} bean does not exist ⇒ the route is not mapped ⇒ POST
 * /strategy-config 404s. Flipping the flag on (test-only) brings it to life. This is what keeps the
 * homelab byte-identical until a manual per-cluster opt-in.
 */
class StrategyConfigDarkProofTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(StrategyConfigController.class)
          .withUserConfiguration(TestSupportConfig.class);

  @Test
  void flagUnset_noStrategyConfigControllerBean() {
    runner.run(ctx -> assertThat(ctx).doesNotHaveBean(StrategyConfigController.class));
  }

  @Test
  void flagOn_strategyConfigControllerBeanExists() {
    runner
        .withPropertyValues("strategy.config.write.enabled=true")
        .run(ctx -> assertThat(ctx).hasSingleBean(StrategyConfigController.class));
  }

  @Configuration
  static class TestSupportConfig {
    @Bean
    WorkflowClient workflowClient() {
      return Mockito.mock(WorkflowClient.class);
    }

    @Bean
    TenantContext tenantContext() {
      return new TenantContext("dev", "copytrade-v1");
    }

    @Bean
    StrategyConfigReader strategyConfigReader() {
      return Mockito.mock(StrategyConfigReader.class);
    }

    @Bean
    VerifiedAccountGuard verifiedAccountGuard() {
      return Mockito.mock(VerifiedAccountGuard.class);
    }
  }
}
