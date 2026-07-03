package com.ohmytradeagent.apigateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.apigateway.security.ServiceTokenFilter;
import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A1 dark proof. With {@code operator.strategy-enable.enabled} UNSET (the repo default), neither
 * {@link OperatorStrategyEnableController} nor {@link ServiceTokenFilter} exists — the enable route
 * is not mapped (404s).
 *
 * <p>The load-bearing assertion is {@link #enableFlagOn_bringsUpControllerAndAuthFilter()}:
 * enabling ONLY the strategy-enable flag must bring up BOTH the controller AND the bearer-gate
 * filter. The filter's {@code @ConditionalOnExpression} has to list EVERY flag that activates an
 * {@code /admin/tenants/} controller, or the cross-tenant enable route would be reachable
 * unauthenticated (a forged {@code X-Operator-Id} could arm arbitrary tenants). This test fails
 * closed if the flag is added to the controller but not the filter.
 */
class StrategyEnableDarkProofTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              OperatorStrategyEnableController.class,
              ServiceTokenFilter.class,
              TestSupportConfig.class);

  @Test
  void flagUnset_noStrategyEnableBeans() {
    runner.run(
        ctx -> {
          assertThat(ctx).doesNotHaveBean(OperatorStrategyEnableController.class);
          assertThat(ctx).doesNotHaveBean(ServiceTokenFilter.class);
        });
  }

  @Test
  void enableFlagOn_bringsUpControllerAndAuthFilter() {
    runner
        .withPropertyValues("operator.strategy-enable.enabled=true")
        .run(
            ctx -> {
              assertThat(ctx).hasSingleBean(OperatorStrategyEnableController.class);
              // The enable flag ALONE must also activate the bearer-gate filter — else the route is
              // unauthenticated.
              assertThat(ctx).hasSingleBean(ServiceTokenFilter.class);
            });
  }

  @Test
  void serviceTokenFilterExpression_listsStrategyEnableFlag() {
    ConditionalOnExpression ann =
        ServiceTokenFilter.class.getAnnotation(ConditionalOnExpression.class);
    assertThat(ann).as("ServiceTokenFilter must be @ConditionalOnExpression-gated").isNotNull();
    assertThat(ann.value()).contains("operator.strategy-enable.enabled");
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
