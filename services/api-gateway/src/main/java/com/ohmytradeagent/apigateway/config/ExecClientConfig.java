package com.ohmytradeagent.apigateway.config;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.client.RestClient;

/**
 * UI-P2-a exec client wiring. Builds the {@link RestClient} the {@link
 * BrokerCredentialForwardService} uses to forward a tenant-entered broker credential to exec's
 * {@code POST /internal/broker-credentials}. DARK by default: the whole config is gated on {@code
 * broker.credentials.write.enabled=true} OR {@code operator.credential-write.enabled=true} (Phase
 * I-1c's operator route shares the same forward pipeline, so its flag must also bring these beans
 * up) OR the A1 arm-guard routes ({@code operator.strategy-enable.enabled=true} / {@code
 * strategy.config.write.enabled=true}), which reuse the SAME {@code execRestClient} for the
 * verified-account read; with all unset (homelab / repo default) the bean does not exist.
 *
 * <p>The {@code Authorization: Bearer <exec admin token>} is a DEFAULT header (the same shared
 * admin token on every request). {@code X-Tenant-Id} is deliberately NOT a default header — it is
 * set per-request by the controller from the authenticated, validated caller tenant, so a
 * stale/global tenant can never leak across requests. No retry: the controller must observe the
 * exec status exactly once and map it to a coarse result + audit outcome.
 */
@Configuration
@ConditionalOnExpression(
    "${broker.credentials.write.enabled:false} or ${operator.credential-write.enabled:false}"
        + " or ${operator.strategy-enable.enabled:false}"
        + " or ${strategy.config.write.enabled:false}"
        + " or ${operator.tenant-delete.enabled:false}")
public class ExecClientConfig {

  @Bean
  public RestClient execRestClient(
      RestClient.Builder builder,
      Environment environment,
      @Value("${exec.base-url}") String execBaseUrl,
      @Value("${exec.admin.service-token:${EXEC_ADMIN_SHARED_TOKEN:}}") String execAdminToken) {
    // Prod fail-fast on the secret-bearing hop: a pod that enables the write flag but forgets the
    // exec admin secret must refuse to boot rather than silently forward credentials under an empty
    // bearer (exec would reject it, but as a confusing 502 — fail clearly at startup instead).
    // Mirrors ServiceTokenFilter's inbound posture.
    if ((execAdminToken == null || execAdminToken.isBlank())
        && environment.acceptsProfiles(Profiles.of("prod"))) {
      throw new IllegalStateException(
          "exec.admin.service-token is blank under the prod profile — set EXEC_ADMIN_SHARED_TOKEN"
              + " to a real secret");
    }
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
   * Per-broker_target exec base URLs ({@code exec.targets.*}) the credential-WRITE forward routes
   * on. Bound here (inside the flag-gated config) so — like every other credential-write bean — it
   * does not exist when the write flags are unset. See {@link ExecTargetProperties}.
   */
  @Bean
  @ConfigurationProperties("exec")
  public ExecTargetProperties execTargetProperties() {
    return new ExecTargetProperties();
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
