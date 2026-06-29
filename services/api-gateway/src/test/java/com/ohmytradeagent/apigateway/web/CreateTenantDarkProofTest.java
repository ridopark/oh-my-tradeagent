package com.ohmytradeagent.apigateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.apigateway.security.ServiceTokenFilter;
import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase I-1b dark proof. With {@code operator.tenant-create.enabled} UNSET (the repo default),
 * neither {@link CreateTenantController} nor {@link ServiceTokenFilter} exists — the create route
 * is not mapped (404s).
 *
 * <p>The load-bearing assertion is {@link #createFlagOn_bringsUpControllerAndAuthFilter()}:
 * enabling ONLY the create flag (NOT broker.credentials.write nor operator.activation) must bring
 * up BOTH the controller AND the bearer-gate filter. The filter's {@code @ConditionalOnExpression}
 * has to list EVERY flag that activates an {@code /admin/tenants/} controller, or the cross-tenant
 * create route would be reachable unauthenticated (a forged {@code X-Operator-Id} could INSERT
 * arbitrary tenant configs). This test fails closed if a future flag is added to the controller but
 * not the filter.
 */
class CreateTenantDarkProofTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              CreateTenantController.class, ServiceTokenFilter.class, TestSupportConfig.class);

  @Test
  void flagUnset_noCreateTenantBeans() {
    runner.run(
        ctx -> {
          assertThat(ctx).doesNotHaveBean(CreateTenantController.class);
          assertThat(ctx).doesNotHaveBean(ServiceTokenFilter.class);
        });
  }

  @Test
  void createFlagOn_bringsUpControllerAndAuthFilter() {
    runner
        .withPropertyValues("operator.tenant-create.enabled=true")
        .run(
            ctx -> {
              assertThat(ctx).hasSingleBean(CreateTenantController.class);
              // The create flag ALONE must also activate the bearer-gate filter — otherwise the
              // route is unauthenticated.
              assertThat(ctx).hasSingleBean(ServiceTokenFilter.class);
            });
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
  }
}
