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
    // interval: read < interval. Without this the default RestClient has no read timeout.
    //
    // TIGHTENED 2026-08-17 alongside the poll interval 2000ms -> 500ms. The old 1s connect +
    // 1500ms read were sized for a 2s interval; left unchanged they would have INVERTED the very
    // invariant this comment states — one slow snapshot could pin a poll thread for 2500ms, five
    // times the new interval, against a fixed 4-thread pool (AlpacaMarketData#defaultScheduler)
    // shared by every OCC poll and the stock reconnect. scheduleAtFixedRate delays overrun tasks
    // rather than running them concurrently, so the symptom would have been the realized cadence
    // silently drifting back toward 1-2s while feed health still showed green.
    //
    // A snapshot that exceeds these is fail-soft by design: pollOnce emits no tick and the poll
    // continues. Dropping one 500ms sample costs far less than starving the pool.
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofMillis(300));
    requestFactory.setReadTimeout(Duration.ofMillis(400));
    return builder
        .baseUrl(props.dataBaseUrl())
        .requestFactory(requestFactory)
        .defaultHeader("APCA-API-KEY-ID", props.apiKeyId())
        .defaultHeader("APCA-API-SECRET-KEY", props.apiSecretKey())
        .defaultHeader("Accept", "application/json")
        .build();
  }
}
