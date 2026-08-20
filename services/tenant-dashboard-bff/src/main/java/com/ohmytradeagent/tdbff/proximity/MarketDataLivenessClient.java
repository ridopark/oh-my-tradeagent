package com.ohmytradeagent.tdbff.proximity;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
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

  /**
   * Per-contract premium-poll liveness from {@code /md/premium-subscriptions}, keyed by the
   * SPACE-PADDED OCC so it joins straight onto a PositionWorkflow's {@code contractSymbol}.
   *
   * <p>Returns null on ANY failure — deliberately distinct from "answered with an empty list".
   * Empty means market-data is up and holds NO subscription for anything, which is the #717 signal
   * and must render as a dead feed. Null means we could not ask, which must render as unknown. A
   * client that conflated them would either cry wolf on every market-data blip or stay silent
   * through a real orphan.
   */
  public PremiumSubscriptions premiumSubscriptions() {
    try {
      Map<String, Object> body =
          rest.get().uri("/md/premium-subscriptions").retrieve().body(MAP_TYPE);
      if (body == null) {
        return null;
      }
      return parsePremiumSubscriptions(body);
    } catch (RuntimeException e) {
      log.warn("market-data premium-subscriptions read failed: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Parses the {@code /md/premium-subscriptions} body. Package-private and static so the wire
   * contract can be tested without standing up HTTP: this is the seam where a renamed key on the
   * market-data side would otherwise fail SILENTLY — every mocked test still green, every badge
   * reading "unknown" forever. The key names here are pinned from the producing side too, by
   * MarketDataQuoteControllerTest.
   *
   * <p>Returns null on any shape it does not recognise, which the caller renders as "unknown"
   * rather than "orphaned".
   */
  static PremiumSubscriptions parsePremiumSubscriptions(Map<String, Object> body) {
    Object rawNow = body.get("now");
    Object rawRows = body.get("subscriptions");
    if (!(rawRows instanceof List<?> rows)) {
      return null;
    }
    Map<String, Map<String, Object>> byOcc = new LinkedHashMap<>();
    for (Object row : rows) {
      if (row instanceof Map<?, ?> m && m.get("occ") instanceof String occ) {
        Map<String, Object> copy = new LinkedHashMap<>();
        m.forEach((k, v) -> copy.put(String.valueOf(k), v));
        byOcc.put(occ, copy);
      }
    }
    return new PremiumSubscriptions(rawNow instanceof String n ? n : null, byOcc);
  }

  /**
   * @param now market-data's OWN wall clock at the moment it answered. Ages are computed against
   *     this, not the BFF's clock: the two are separate pods and at a 500ms poll a couple of
   *     seconds of drift is the whole difference between "alive" and "dead".
   * @param byOcc space-padded OCC to that contract's raw status row; a contract ABSENT from this
   *     map has no live subscription
   */
  public record PremiumSubscriptions(String now, Map<String, Map<String, Object>> byOcc) {}

  private static Map<String, Object> unknown() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("status", "unknown");
    return out;
  }
}
