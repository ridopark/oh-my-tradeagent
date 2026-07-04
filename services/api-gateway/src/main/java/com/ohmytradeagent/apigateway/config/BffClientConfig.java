package com.ohmytradeagent.apigateway.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.client.RestClient;

/**
 * Operator tenant-delete (PLAN-2026-07-03, Phase 4) BFF client wiring. Builds the {@link
 * RestClient} the {@link com.ohmytradeagent.apigateway.web.DashboardRowsDeleteForwarder} uses to
 * call the BFF's {@code DELETE /api/admin/tenants/{tenant}/dashboard-rows} (Phase 3) — the one
 * store api-gateway cannot reach directly. DARK by construction: gated on {@code
 * operator.tenant-delete.enabled=true}, so with the flag unset (homelab / repo default) the bean
 * does not exist.
 *
 * <p>The BFF route is service-token bearer-gated, so the {@code Authorization: Bearer <bff service
 * token>} is a DEFAULT header. The {@code X-Operator-Id} (the BFF also requires an allowlisted
 * operator) is set per-request by the forwarder from the authenticated caller. Mirrors {@link
 * ExecClientConfig}: prod fail-fast on a blank token, bounded timeouts, no retry.
 */
@Configuration
@ConditionalOnProperty(name = "operator.tenant-delete.enabled", havingValue = "true")
public class BffClientConfig {

  @Bean
  public RestClient bffRestClient(
      RestClient.Builder builder,
      Environment environment,
      @Value("${bff.base-url:http://tenant-dashboard-bff:8083}") String bffBaseUrl,
      @Value("${bff.service-token:${BFF_SHARED_TOKEN:}}") String bffToken) {
    if ((bffToken == null || bffToken.isBlank())
        && environment.acceptsProfiles(Profiles.of("prod"))) {
      throw new IllegalStateException(
          "bff.service-token is blank under the prod profile — set BFF_SHARED_TOKEN to a real"
              + " secret");
    }
    ClientHttpRequestFactorySettings settings =
        ClientHttpRequestFactorySettings.defaults()
            .withConnectTimeout(Duration.ofSeconds(3))
            .withReadTimeout(Duration.ofSeconds(15));
    return builder
        .baseUrl(bffBaseUrl)
        .defaultHeader("Authorization", "Bearer " + bffToken)
        .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
        .build();
  }
}
