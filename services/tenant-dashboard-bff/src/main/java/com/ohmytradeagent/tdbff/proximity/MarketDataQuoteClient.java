package com.ohmytradeagent.tdbff.proximity;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reads underlying-equity last-trade prices from the market-data worker's {@code
 * /actuator/equityquote/{ticker}} endpoint, for the dashboard /live underlying spot column.
 *
 * <p>Fail-soft like {@link MarketDataLivenessClient}: market-data is a separate deployment, so a
 * timeout/unreachable/missing price degrades to {@code null} (the row renders "-") rather than
 * failing the proximity response. Short timeouts so an unreachable worker cannot pin the request
 * thread.
 */
@Component
public class MarketDataQuoteClient {

  private static final Logger log = LoggerFactory.getLogger(MarketDataQuoteClient.class);
  private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
      new ParameterizedTypeReference<>() {};

  private final RestClient rest;

  public MarketDataQuoteClient(
      @Value("${market-data.base-url:http://market-data:8080}") String baseUrl) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(2));
    factory.setReadTimeout(Duration.ofSeconds(2));
    this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
  }

  /** Underlying last-trade price for {@code ticker}, or null when unavailable. */
  public BigDecimal equityPrice(String ticker) {
    try {
      Map<String, Object> body =
          rest.get().uri("/actuator/equityquote/{t}", ticker).retrieve().body(MAP_TYPE);
      Object price = body == null ? null : body.get("price");
      return price == null ? null : new BigDecimal(price.toString());
    } catch (RuntimeException e) {
      log.warn("market-data equityquote read failed for {}: {}", ticker, e.getMessage());
      return null;
    }
  }
}
