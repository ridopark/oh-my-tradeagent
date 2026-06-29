package com.ohmytradeagent.apigateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.ohmytradeagent.apigateway.security.CredentialWriteLimiter;
import com.ohmytradeagent.apigateway.security.ServiceTokenFilter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.temporal.client.WorkflowClient;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Phase I-1c dark proof. With {@code operator.credential-write.enabled} UNSET (the repo default),
 * neither {@link OperatorBrokerCredentialController} nor {@link ServiceTokenFilter} exists — the
 * operator credential-write route is not mapped (404s).
 *
 * <p>The load-bearing assertion is {@link #operatorFlagOn_bringsUpControllerAndAuthFilter()}:
 * enabling ONLY the operator credential-write flag (NOT broker.credentials.write nor the other
 * operator flags) must bring up BOTH the controller AND the bearer-gate filter. The filter's
 * {@code @ConditionalOnExpression} has to list EVERY flag that activates an {@code /admin/tenants/}
 * controller, or the cross-tenant credential-write route would be reachable unauthenticated (a
 * forged {@code X-Operator-Id} could push arbitrary broker keys into a tenant). This fails closed
 * if a future change adds the controller but forgets the filter.
 *
 * <p>{@link #operatorFlagOn_bringsUpSharedPipeline()} additionally pins that the shared {@link
 * BrokerCredentialForwardService} and {@link CredentialWriteLimiter} (whose conditions were relaxed
 * to OR-in this flag) actually come up under it alone — without them the route's bean wiring would
 * fail to start.
 */
class OperatorCredentialWriteDarkProofTest {

  private final ApplicationContextRunner controllerRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              OperatorBrokerCredentialController.class,
              ServiceTokenFilter.class,
              ControllerSupportConfig.class);

  private final ApplicationContextRunner pipelineRunner =
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
          .withUserConfiguration(
              BrokerCredentialForwardService.class,
              CredentialWriteLimiter.class,
              PipelineSupportConfig.class);

  @Test
  void flagUnset_noOperatorCredentialBeans() {
    controllerRunner.run(
        ctx -> {
          assertThat(ctx).doesNotHaveBean(OperatorBrokerCredentialController.class);
          assertThat(ctx).doesNotHaveBean(ServiceTokenFilter.class);
        });
    pipelineRunner.run(
        ctx -> {
          assertThat(ctx).doesNotHaveBean(BrokerCredentialForwardService.class);
          assertThat(ctx).doesNotHaveBean(CredentialWriteLimiter.class);
        });
  }

  @Test
  void operatorFlagOn_bringsUpControllerAndAuthFilter() {
    controllerRunner
        .withPropertyValues("operator.credential-write.enabled=true")
        .run(
            ctx -> {
              assertThat(ctx).hasSingleBean(OperatorBrokerCredentialController.class);
              // The operator credential-write flag ALONE must also activate the bearer-gate filter
              // — otherwise the /admin/tenants/.../broker-credentials route is unauthenticated.
              assertThat(ctx).hasSingleBean(ServiceTokenFilter.class);
            });
  }

  @Test
  void operatorFlagOn_bringsUpSharedPipeline() {
    pipelineRunner
        .withPropertyValues("operator.credential-write.enabled=true")
        .run(
            ctx -> {
              assertThat(ctx).hasSingleBean(BrokerCredentialForwardService.class);
              assertThat(ctx).hasSingleBean(CredentialWriteLimiter.class);
            });
  }

  @Configuration
  static class ControllerSupportConfig {
    @Bean
    BrokerCredentialForwardService brokerCredentialForwardService() {
      return mock(BrokerCredentialForwardService.class);
    }

    @Bean
    TenantContext tenantContext() {
      return new TenantContext("dev", "copytrade-v1");
    }
  }

  @Configuration
  static class PipelineSupportConfig {
    @Bean
    RestClient execRestClient() {
      return mock(RestClient.class);
    }

    @Bean
    WorkflowClient workflowClient() {
      return mock(WorkflowClient.class);
    }

    @Bean
    Clock brokerCredentialClock() {
      return Clock.systemUTC().withZone(ZoneOffset.UTC);
    }

    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }
}
