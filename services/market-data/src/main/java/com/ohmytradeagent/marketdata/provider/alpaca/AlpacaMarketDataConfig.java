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
    // Bounded timeouts so a slow Alpaca snapshot cannot pin a caller indefinitely. Without this
    // the default RestClient has no read timeout at all.
    //
    // DELIBERATELY NOT TIGHTENED when the premium poll went 2000ms -> 500ms on 2026-08-17, even
    // though the original comment framed the rule as "read < interval" and 1500ms now exceeds the
    // 500ms interval. A first attempt did tighten these to 300/400ms; it was reverted, because
    // THIS CLIENT IS SHARED and the two callers have opposite failure semantics:
    //
    //   - pollOnce (premium poll, 2/sec/contract) is fail-SOFT: a timeout emits no tick, the next
    //     poll is 500ms away, and an over-running poll merely delays its own next run
    //     (scheduleAtFixedRate defers rather than piling up). Self-limiting.
    //   - snapshotQuote via GetOptionQuoteActivityImpl feeds the ACCOUNT KILL-SWITCH MTM
    //     heartbeat, which is fail-CLOSED: an unavailable quote trips the account cap with
    //     auto:account_mtm_unavailable. That is exactly the 2026-07-21 incident, where a single
    //     quote miss fail-closed prod_real on a PROFITABLE day.
    //
    // So a tighter timeout buys a marginally crisper poll cadence and pays for it with a higher
    // chance of tripping a real-money safety gate on a slow response. Wrong trade. If poll-thread
    // starvation ever actually shows up (watch for the realized interval drifting toward 1-2s),
    // the fix is a SEPARATE RestClient for pollOnce with its own tight timeouts — not tightening
    // the one the kill switch depends on.
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
