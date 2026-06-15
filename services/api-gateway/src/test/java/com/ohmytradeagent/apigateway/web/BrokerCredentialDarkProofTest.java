package com.ohmytradeagent.apigateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.apigateway.config.ExecClientConfig;
import com.ohmytradeagent.apigateway.security.CredentialWriteLimiter;
import com.ohmytradeagent.apigateway.security.ServiceTokenFilter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * UI-P2-a dark proof. With {@code broker.credentials.write.enabled} UNSET (the repo default), NONE
 * of the new beans exist — {@link BrokerCredentialController}, {@code execRestClient}, the {@code
 * brokerCredentialClock}, and {@link ServiceTokenFilter}. (No controller bean ⇒ the route is not
 * mapped ⇒ POST /broker-credentials 404s.) Flipping the flag on (test-only) brings them all to
 * life. This is what keeps the homelab byte-identical until a manual per-cluster opt-in.
 */
class BrokerCredentialDarkProofTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          // Spring Boot registers ApplicationConversionService on the real context; this slice
          // harness does not, so the limiter's @Value ISO-8601 Duration defaults (PT10M/PT15M)
          // would fail String→Duration conversion. Register it to mirror the production context.
          .withInitializer(
              ctx ->
                  ctx.getBeanFactory()
                      .setConversionService(
                          org.springframework.boot.convert.ApplicationConversionService
                              .getSharedInstance()))
          .withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
          .withUserConfiguration(
              ExecClientConfig.class,
              ServiceTokenFilter.class,
              CredentialWriteLimiter.class,
              BrokerCredentialController.class)
          .withUserConfiguration(TestSupportConfig.class)
          .withPropertyValues("exec.base-url=http://exec:8080");

  @Test
  void flagUnset_noCredentialBeansExist() {
    runner.run(
        ctx -> {
          assertThat(ctx).doesNotHaveBean(BrokerCredentialController.class);
          assertThat(ctx).doesNotHaveBean(ServiceTokenFilter.class);
          assertThat(ctx).doesNotHaveBean(CredentialWriteLimiter.class);
          assertThat(ctx).doesNotHaveBean("execRestClient");
          assertThat(ctx).doesNotHaveBean("brokerCredentialClock");
        });
  }

  @Test
  void flagOn_allCredentialBeansExist() {
    runner
        .withPropertyValues("broker.credentials.write.enabled=true")
        .run(
            ctx -> {
              assertThat(ctx).hasSingleBean(BrokerCredentialController.class);
              assertThat(ctx).hasSingleBean(ServiceTokenFilter.class);
              assertThat(ctx).hasSingleBean(CredentialWriteLimiter.class);
              assertThat(ctx).hasBean("execRestClient");
              assertThat(ctx).hasBean("brokerCredentialClock");
            });
  }

  /**
   * Collaborators the gated beans depend on; always present so only the FLAG decides activation.
   */
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
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }
}
