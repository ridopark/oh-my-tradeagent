package com.ohmytradeagent.tdbff.proximity;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reads the market-data worker's custom {@code /actuator/feedhealth} JSON (per-feed WS connected +
 * last-tick age) for the dashboard liveness strip.
 *
 * <p>Fail-soft: market-data is a different deployment, so a timeout/unreachable/parse error must
 * NOT fail the whole proximity response — it degrades to {@code {status: "unknown"}} and the
 * proximity tables still render. Connect/read timeouts are short so an unreachable worker cannot
 * pin the Spring MVC request thread.
 */
@Component
public class MarketDataLivenessClient {

  private static final Logger log = LoggerFactory.getLogger(MarketDataLivenessClient.class);
  private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
      new ParameterizedTypeReference<>() {};

  private final RestClient rest;

  public MarketDataLivenessClient(
      @Value("${market-data.base-url:http://market-data:8080}") String baseUrl) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(2));
    factory.setReadTimeout(Duration.ofSeconds(2));
    this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
  }

  /**
   * Per-feed liveness with a {@code status} marker: {@code "ok"} when the actuator answered, {@code
   * "unknown"} on any failure. Shape on success: {@code {status, equity:{connected,lastTickAgeMs},
   * option:{connected,lastTickAgeMs}}}.
   */
  public Map<String, Object> feedHealth() {
    try {
      Map<String, Object> body = rest.get().uri("/actuator/feedhealth").retrieve().body(MAP_TYPE);
      if (body == null || body.isEmpty()) {
        return unknown();
      }
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("status", "ok");
      out.putAll(body);
      return out;
    } catch (RuntimeException e) {
      log.warn("market-data feedhealth read failed: {}", e.getMessage());
      return unknown();
    }
  }

  private static Map<String, Object> unknown() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("status", "unknown");
    return out;
  }
}
