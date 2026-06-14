package com.ohmytradeagent.exec.broker.alpaca;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Spring wiring for the Alpaca adapter. Activated for any {@code alpaca-*} broker.impl (both {@code
 * alpaca-paper} and {@code alpaca-live}), so containers that boot with {@code BROKER_IMPL=stub}
 * (CI, idempotency ITs) never construct a RestClient or check Alpaca credentials.
 */
@Configuration
@ConditionalOnExpression("'${broker.impl:}'.startsWith('alpaca-')")
@EnableConfigurationProperties(AlpacaProperties.class)
public class AlpacaConfig {

  /**
   * Builds the shared {@link RestClient} pre-wired with Alpaca's base URL and auth headers. Fails
   * fast on missing creds: a misconfigured deployment must not silently boot and then 401 on every
   * order — that wastes Temporal Activity retries and pollutes the audit log. Also fails fast when
   * the impl and base URL disagree (a {@code -live} impl pointed at the paper host, or vice versa),
   * so a real-money build can never silently route to paper and a paper build can never route to
   * live.
   */
  @Bean
  RestClient alpacaRestClient(
      AlpacaProperties props,
      RestClient.Builder builder,
      @Value("${broker.impl:}") String brokerImpl) {
    if (props.apiKeyId() == null || props.apiKeyId().isBlank()) {
      throw new IllegalStateException(
          "broker.impl=alpaca-* requires APCA_API_KEY_ID; got blank/null. "
              + "Set the alpaca-credentials Secret in your deployment.");
    }
    if (props.apiSecretKey() == null || props.apiSecretKey().isBlank()) {
      throw new IllegalStateException(
          "broker.impl=alpaca-* requires APCA_API_SECRET_KEY; got blank/null. "
              + "Set the alpaca-credentials Secret in your deployment.");
    }
    boolean baseUrlIsPaper = props.baseUrl() != null && props.baseUrl().contains("paper");
    if (brokerImpl.endsWith("-live") && baseUrlIsPaper) {
      throw new IllegalStateException(
          "broker.impl="
              + brokerImpl
              + " (live) must not target a paper endpoint; "
              + "alpaca.base-url="
              + props.baseUrl()
              + ". Point it at the live host.");
    }
    if (brokerImpl.endsWith("-paper") && !baseUrlIsPaper) {
      throw new IllegalStateException(
          "broker.impl="
              + brokerImpl
              + " (paper) must target a paper endpoint; "
              + "alpaca.base-url="
              + props.baseUrl()
              + ". Point it at the paper host.");
    }
    return builder
        .baseUrl(props.baseUrl())
        .defaultHeader("APCA-API-KEY-ID", props.apiKeyId())
        .defaultHeader("APCA-API-SECRET-KEY", props.apiSecretKey())
        .defaultHeader("Accept", "application/json")
        .build();
  }
}
