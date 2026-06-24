package com.ohmytradeagent.marketdata.provider.alpaca;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Spring wiring for the Alpaca market-data adapter. Activated only when {@code
 * market-data.provider=alpaca}, so containers that boot with {@code MARKET_DATA_PROVIDER=inmemory}
 * (CI, dev) never construct a RestClient or check Alpaca credentials.
 *
 * <p>Auth headers match the same {@code APCA-API-KEY-ID} / {@code APCA-API-SECRET-KEY} pattern as
 * the exec-svc Alpaca broker, intentionally — the same alpaca-credentials Secret feeds both pods.
 */
@Configuration
@ConditionalOnProperty(name = "market-data.provider", havingValue = "alpaca")
@EnableConfigurationProperties(AlpacaMarketDataProperties.class)
public class AlpacaMarketDataConfig {

  @Bean
  RestClient alpacaMarketDataRestClient(
      AlpacaMarketDataProperties props, RestClient.Builder builder) {
    if (props.apiKeyId() == null || props.apiKeyId().isBlank()) {
      throw new IllegalStateException(
          "market-data.provider=alpaca requires APCA_API_KEY_ID_DATA; got blank/null. "
              + "Set the alpaca-credentials Secret in your deployment.");
    }
    if (props.apiSecretKey() == null || props.apiSecretKey().isBlank()) {
      throw new IllegalStateException(
          "market-data.provider=alpaca requires APCA_API_SECRET_KEY_DATA; got blank/null. "
              + "Set the alpaca-credentials Secret in your deployment.");
    }
    // Bounded timeouts so a slow Alpaca snapshot cannot pin a premium-poll thread past the poll
    // interval (default 2s): read < interval. Without this the default RestClient has no read
    // timeout.
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(1));
    requestFactory.setReadTimeout(Duration.ofMillis(1500));
    return builder
        .baseUrl(props.dataBaseUrl())
        .requestFactory(requestFactory)
        .defaultHeader("APCA-API-KEY-ID", props.apiKeyId())
        .defaultHeader("APCA-API-SECRET-KEY", props.apiSecretKey())
        .defaultHeader("Accept", "application/json")
        .build();
  }
}
