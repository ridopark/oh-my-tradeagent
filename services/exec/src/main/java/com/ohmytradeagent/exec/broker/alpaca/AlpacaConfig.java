package com.ohmytradeagent.exec.broker.alpaca;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Spring wiring for the Alpaca adapter. Activated only when {@code broker.impl=alpaca-paper}, so
 * containers that boot with {@code BROKER_IMPL=stub} (CI, idempotency ITs) never construct a
 * RestClient or check Alpaca credentials.
 */
@Configuration
@ConditionalOnProperty(name = "broker.impl", havingValue = "alpaca-paper")
@EnableConfigurationProperties(AlpacaProperties.class)
public class AlpacaConfig {

  /**
   * Builds the shared {@link RestClient} pre-wired with Alpaca's base URL and auth headers. Fails
   * fast on missing creds: a misconfigured paper deployment must not silently boot and then 401 on
   * every order — that wastes Temporal Activity retries and pollutes the audit log.
   */
  @Bean
  RestClient alpacaRestClient(AlpacaProperties props, RestClient.Builder builder) {
    if (props.apiKeyId() == null || props.apiKeyId().isBlank()) {
      throw new IllegalStateException(
          "broker.impl=alpaca-paper requires APCA_API_KEY_ID; got blank/null. "
              + "Set the alpaca-credentials Secret in your deployment.");
    }
    if (props.apiSecretKey() == null || props.apiSecretKey().isBlank()) {
      throw new IllegalStateException(
          "broker.impl=alpaca-paper requires APCA_API_SECRET_KEY; got blank/null. "
              + "Set the alpaca-credentials Secret in your deployment.");
    }
    return builder
        .baseUrl(props.baseUrl())
        .defaultHeader("APCA-API-KEY-ID", props.apiKeyId())
        .defaultHeader("APCA-API-SECRET-KEY", props.apiSecretKey())
        .defaultHeader("Accept", "application/json")
        .build();
  }
}
