package com.ohmytradeagent.apigateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * account-loss-cap-db (Phase 3) dark proof. With {@code tenant.config.write.enabled} UNSET (the
 * repo default), the {@link TenantConfigController} bean does not exist ⇒ the route is not mapped ⇒
 * POST /tenant-config 404s. Flipping the flag on (test-only) brings it to life. This keeps the
 * homelab byte-identical until a manual per-cluster opt-in (post risk-manager sign-off).
 */
class TenantConfigDarkProofTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(TenantConfigController.class)
          .withUserConfiguration(TestSupportConfig.class);

  @Test
  void flagUnset_noTenantConfigControllerBean() {
    runner.run(ctx -> assertThat(ctx).doesNotHaveBean(TenantConfigController.class));
  }

  @Test
  void flagOn_tenantConfigControllerBeanExists() {
    runner
        .withPropertyValues("tenant.config.write.enabled=true")
        .run(ctx -> assertThat(ctx).hasSingleBean(TenantConfigController.class));
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
