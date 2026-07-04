package com.ohmytradeagent.apigateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.apigateway.config.BffClientConfig;
import com.ohmytradeagent.apigateway.config.ExecClientConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
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
              "bff.base-url=http://tenant-dashboard-bff:8080",
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
}
