package com.ohmytradeagent.apigateway.config;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * UI-P2-a exec client wiring. Builds the {@link RestClient} the {@code BrokerCredentialController}
 * uses to forward a tenant-entered broker credential to exec's {@code POST
 * /internal/broker-credentials}. DARK by default: the whole config is gated on {@code
 * broker.credentials.write.enabled=true}, so with the flag unset (homelab / repo default) the bean
 * does not exist.
 *
 * <p>The {@code Authorization: Bearer <exec admin token>} is a DEFAULT header (the same shared
 * admin token on every request). {@code X-Tenant-Id} is deliberately NOT a default header — it is
 * set per-request by the controller from the authenticated, validated caller tenant, so a
 * stale/global tenant can never leak across requests. No retry: the controller must observe the
 * exec status exactly once and map it to a coarse result + audit outcome.
 */
@Configuration
@ConditionalOnProperty(name = "broker.credentials.write.enabled", havingValue = "true")
public class ExecClientConfig {

  @Bean
  public RestClient execRestClient(
      RestClient.Builder builder,
      @Value("${exec.base-url}") String execBaseUrl,
      @Value("${exec.admin.service-token:${EXEC_ADMIN_SHARED_TOKEN:}}") String execAdminToken) {
    ClientHttpRequestFactorySettings settings =
        ClientHttpRequestFactorySettings.defaults()
            .withConnectTimeout(Duration.ofSeconds(3))
            .withReadTimeout(Duration.ofSeconds(15));
    return builder
        .baseUrl(execBaseUrl)
        .defaultHeader("Authorization", "Bearer " + execAdminToken)
        .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
        .build();
  }

  /**
   * UTC clock for the credential-write controller (audit {@code occurred_at} + the rate-limit
   * window). Lives inside this flag-gated config so it — like every other UI-P2-a bean — does not
   * exist when {@code broker.credentials.write.enabled} is unset. Tests inject a fixed clock.
   */
  @Bean
  public Clock brokerCredentialClock() {
    return Clock.systemUTC();
  }
}
