package com.ohmytradeagent.apigateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.ohmytradeagent.apigateway.security.ServiceTokenFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 4 dark proof. With {@code operator.tenant-delete.enabled} UNSET (the repo default), neither
 * {@link TenantDeleteController} nor {@link ServiceTokenFilter} exists — the delete route 404s.
 * Enabling ONLY the delete flag must bring up BOTH the controller AND the bearer-gate filter
 * (otherwise the real-money-adjacent delete route would be reachable unauthenticated).
 */
class TenantDeleteDarkProofTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              TenantDeleteController.class, ServiceTokenFilter.class, TestSupportConfig.class);

  @Test
  void flagUnset_noTenantDeleteBeans() {
    runner.run(
        ctx -> {
          assertThat(ctx).doesNotHaveBean(TenantDeleteController.class);
          assertThat(ctx).doesNotHaveBean(ServiceTokenFilter.class);
        });
  }

  @Test
  void deleteFlagOn_bringsUpControllerAndAuthFilter() {
    runner
        .withPropertyValues("operator.tenant-delete.enabled=true")
        .run(
            ctx -> {
              assertThat(ctx).hasSingleBean(TenantDeleteController.class);
              assertThat(ctx).hasSingleBean(ServiceTokenFilter.class);
            });
  }

  /**
   * The delete flag MUST appear in {@link ServiceTokenFilter}'s {@code @ConditionalOnExpression},
   * or the {@code /admin/tenants/{tenant}/delete} route would be reachable with the bearer gate
   * absent.
   */
  @Test
  void serviceTokenFilterExpression_listsTenantDeleteFlag() {
    ConditionalOnExpression ann =
        ServiceTokenFilter.class.getAnnotation(ConditionalOnExpression.class);
    assertThat(ann).isNotNull();
    assertThat(ann.value()).contains("operator.tenant-delete.enabled");
  }

  @Configuration
  static class TestSupportConfig {
    @Bean
    TenantContext tenantContext() {
      return new TenantContext("dev", "copytrade-v1");
    }

    @Bean
    StrategyConfigReader strategyConfigReader() {
      return mock(StrategyConfigReader.class);
    }

    @Bean
    LiveActivationStateReader liveActivationStateReader() {
      return mock(LiveActivationStateReader.class);
    }

    @Bean
    OpenPositionWorkflowChecker openPositionWorkflowChecker() {
      return mock(OpenPositionWorkflowChecker.class);
    }

    @Bean
    StrategyDisableClient strategyDisableClient() {
      return mock(StrategyDisableClient.class);
    }

    @Bean
    TenantDeleteWorkflowClient tenantDeleteWorkflowClient() {
      return mock(TenantDeleteWorkflowClient.class);
    }

    @Bean
    BrokerCredentialDeleteForwarder brokerCredentialDeleteForwarder() {
      return mock(BrokerCredentialDeleteForwarder.class);
    }

    @Bean
    DashboardRowsDeleteForwarder dashboardRowsDeleteForwarder() {
      return mock(DashboardRowsDeleteForwarder.class);
    }

    @Bean
    TenantDeleteHistoryReader tenantDeleteHistoryReader() {
      return mock(TenantDeleteHistoryReader.class);
    }

    @Bean
    TenantDeleteAuditEmitter tenantDeleteAuditEmitter() {
      return mock(TenantDeleteAuditEmitter.class);
    }
  }
}
