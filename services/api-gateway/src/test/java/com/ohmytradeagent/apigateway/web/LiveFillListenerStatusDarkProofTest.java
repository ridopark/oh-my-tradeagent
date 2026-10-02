package com.ohmytradeagent.apigateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.apigateway.config.ExecClientConfig;
import com.ohmytradeagent.apigateway.config.ExecTargetConfig;
import com.ohmytradeagent.apigateway.security.ServiceTokenFilter;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * With {@code live.fill-listener-status.enabled} unset the route does not exist. Turning ON only
 * that flag must bring up the controller, its routing (resolver + exec.targets) AND the bearer gate
 * — but NOT the secret-bearing {@code execRestClient}.
 */
class LiveFillListenerStatusDarkProofTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
          .withUserConfiguration(
              LiveFillListenerStatusController.class,
              TenantBrokerTargetResolver.class,
              ExecTargetConfig.class,
              ExecClientConfig.class,
              ServiceTokenFilter.class,
              TestSupportConfig.class)
          .withPropertyValues("exec.base-url=http://exec:8080");

  @Test
  void flagUnset_noRouteNoGate() {
    runner.run(
        ctx -> {
          assertThat(ctx).doesNotHaveBean(LiveFillListenerStatusController.class);
          assertThat(ctx).doesNotHaveBean(TenantBrokerTargetResolver.class);
          assertThat(ctx).doesNotHaveBean("execTargetProperties");
          assertThat(ctx).doesNotHaveBean(ServiceTokenFilter.class);
        });
  }

  @Test
  void flagOn_bringsUpRouteRoutingAndAuthFilter_butNotExecAdminClient() {
    runner
        .withPropertyValues(
            "live.fill-listener-status.enabled=true",
            "exec.targets.alpaca-live=http://exec-alpaca-live:8080")
        .run(
            ctx -> {
              assertThat(ctx).hasSingleBean(LiveFillListenerStatusController.class);
              assertThat(ctx).hasSingleBean(TenantBrokerTargetResolver.class);
              assertThat(ctx).hasBean("execTargetProperties");
              assertThat(ctx).hasSingleBean(ServiceTokenFilter.class);
              assertThat(ctx).doesNotHaveBean("execRestClient");
            });
  }

  @Configuration
  static class TestSupportConfig {
    @Bean
    DSLContext dslContext() {
      return Mockito.mock(DSLContext.class);
    }

    @Bean
    TenantContext tenantContext() {
      return new TenantContext("dev", "copytrade-v1");
    }
  }
}
