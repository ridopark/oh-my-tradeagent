package com.ohmytradeagent.exec.broker.alpaca;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Spring wiring for the Alpaca adapter. Activated for any {@code alpaca-*} broker.impl (both {@code
 * alpaca-paper} and {@code alpaca-live}), so containers that boot with {@code BROKER_IMPL=stub}
 * (CI, idempotency ITs) never bind Alpaca config.
 *
 * <p>P4-a: the single shared {@code alpacaRestClient} {@code @Bean} is gone — the {@link
 * com.ohmytradeagent.exec.broker.BrokerClientRegistry} now builds a {@link RestClient} PER {@code
 * (tenant, account)} key via {@link #buildRestClient}. This class is retained only as the
 * {@code @EnableConfigurationProperties(AlpacaProperties.class)} holder and the home of the shared
 * RestClient header-wiring factory (single source of truth so the registry's per-key build matches
 * the historical wiring byte-for-byte). The cred-presence + mode-coherence fail-fast guards moved
 * to {@link AlpacaModeCoherence}; the boot account probe moved to a registry warm-up ({@link
 * AlpacaAccountIdentityProbe}).
 */
@Configuration
@ConditionalOnExpression("'${broker.impl:}'.startsWith('alpaca-')")
@EnableConfigurationProperties(AlpacaProperties.class)
public class AlpacaConfig {

  /**
   * Builds a {@link RestClient} pre-wired with Alpaca's base URL and auth headers, exactly as the
   * pre-P4-a {@code alpacaRestClient} bean did (same {@code APCA-API-KEY-ID} / {@code
   * APCA-API-SECRET-KEY} / {@code Accept} headers) so the registry's per-key client is
   * byte-identical on the wire. Cred presence + mode coherence are enforced by the caller via
   * {@link AlpacaModeCoherence} before this is invoked.
   */
  public static RestClient buildRestClient(
      RestClient.Builder builder, String baseUrl, String apiKeyId, String apiSecretKey) {
    return builder
        .baseUrl(baseUrl)
        .defaultHeader("APCA-API-KEY-ID", apiKeyId)
        .defaultHeader("APCA-API-SECRET-KEY", apiSecretKey)
        .defaultHeader("Accept", "application/json")
        .build();
  }
}
