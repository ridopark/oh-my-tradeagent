package com.ohmytradeagent.apigateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.ohmytradeagent.apigateway.config.BffClientConfig;
import com.ohmytradeagent.apigateway.config.ExecClientConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Regression guard for the operator tenant-delete startup crash (fix/tenant-delete-forwarder-
 * restclient-qualifier). When {@code operator.tenant-delete.enabled=true}, BOTH {@link
 * ExecClientConfig#execRestClient} and {@link BffClientConfig#bffRestClient} are present. The
 * forwarders must inject their target client by explicit {@code @Qualifier}, NOT by relying on
 * constructor-parameter-name matching — the api-gateway build does not compile with {@code
 * -parameters}, so name-based disambiguation is unavailable and an unqualified {@code RestClient}
 * injection fails at context startup with {@code NoUniqueBeanDefinitionException} ("expected single
 * matching bean but found 2: bffRestClient,execRestClient"), CrashLoopBackOff-ing the pod.
 *
 * <p>This test wires the REAL beans (no mocked forwarders — the WebMvc slices mock them, which is
 * why they never caught this) with both RestClient beans present, and asserts the context starts
 * and both forwarders resolve. Remove either {@code @Qualifier} on the forwarder ctors and this
 * test fails with the exact production error.
 */
class TenantDeleteForwarderWiringTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
          .withUserConfiguration(
              ExecClientConfig.class,
              BffClientConfig.class,
              BrokerCredentialDeleteForwarder.class,
              DashboardRowsDeleteForwarder.class)
          .withPropertyValues(
              "operator.tenant-delete.enabled=true",
              "exec.base-url=http://exec:8080",
              "exec.admin.service-token=test-exec-admin-token",
              "bff.base-url=http://tenant-dashboard-bff:8083",
              "bff.service-token=test-bff-token");

  @Test
  void tenantDeleteArmed_bothRestClientBeansPresent_contextStartsAndForwardersWireExplicitly() {
    runner.run(
        context -> {
          // Precondition: both RestClient beans exist — this is the ambiguity the @Qualifier
          // annotations must resolve. If only one were present the test would prove nothing.
          assertThat(context.getBeansOfType(RestClient.class))
              .containsOnlyKeys("execRestClient", "bffRestClient");

          // The regression: without @Qualifier the context fails to start here.
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(BrokerCredentialDeleteForwarder.class);
          assertThat(context).hasSingleBean(DashboardRowsDeleteForwarder.class);
        });
  }

  /**
   * FIX 2 root-cause regression guard: the {@code bff.base-url} DEFAULT (no {@code BFF_BASE_URL} /
   * property override — as on the live api-gateway deployment, which sets no BFF env) must target
   * the BFF's real listen port {@code 8083} — the value {@code
   * infra/k8s/58-tenant-dashboard-bff.yaml} Service and {@code TENANT_DASHBOARD_BFF_PORT} expose.
   * The staging-paper-2 delete failed the dashboard_user hop with a {@code ResourceAccessException}
   * because the default was the wrong port ({@code 8080}) → the api-gateway connected to a dead
   * port.
   *
   * <p>Sets NO {@code bff.base-url}, so {@link BffClientConfig}'s
   * {@code @Value("${bff.base-url:...}")} default is exercised. A spy {@link RestClient.Builder}
   * captures the exact {@code baseUrl(...)} the {@code bffRestClient} bean applies from that
   * default; the assertion fails if it regresses to {@code :8080} (or any non-8083 port). Directly
   * reproduces + guards the live failure.
   */
  @Test
  void bffBaseUrl_default_targetsBffListenPort8083() {
    // Only BffClientConfig is wired (no exec bean), so the sole builder.baseUrl(...) call is the
    // bff one — its argument is the resolved @Value default.
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
        .withUserConfiguration(BffClientConfig.class, SpyBuilderConfig.class)
        .withPropertyValues(
            "operator.tenant-delete.enabled=true",
            // No bff.base-url → exercise the BffClientConfig code default (must be :8083).
            "bff.service-token=test-bff-token")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              RestClient.Builder spyBuilder = context.getBean(RestClient.Builder.class);
              ArgumentCaptor<String> baseUrl = ArgumentCaptor.forClass(String.class);
              verify(spyBuilder, atLeastOnce()).baseUrl(baseUrl.capture());
              // The default the bffRestClient bean applied — MUST be the BFF's real listen port.
              assertThat(baseUrl.getValue())
                  .isEqualTo("http://tenant-dashboard-bff:8083")
                  .endsWith(":8083")
                  .doesNotEndWith(":8080");
            });
  }

  /**
   * Supplies a Mockito spy {@link RestClient.Builder} (all real behavior via the spy delegate) so
   * the test can capture the {@code baseUrl(...)} argument the {@code bffRestClient} bean applies
   * from its {@code @Value} default.
   */
  @Configuration
  static class SpyBuilderConfig {
    @Bean
    RestClient.Builder restClientBuilder() {
      return spy(RestClient.builder());
    }
  }
}
