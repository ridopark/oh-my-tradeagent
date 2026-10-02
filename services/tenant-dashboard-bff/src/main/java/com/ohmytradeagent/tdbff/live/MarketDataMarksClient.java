package com.ohmytradeagent.tdbff.live;

import java.time.Duration;
import java.util.ArrayList;
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
 * Reads market-data's display marks ({@code GET /md/marks?occ=...}, compact OCCs) for the 1s
 * holdings poll. Short (1s) timeouts: this sits on a 1Hz path and must never pin a request thread.
 * Returns null on ANY failure, which the caller renders as {@code market_data_reachable:false} —
 * distinct from an answer with no marks.
 */
@Component
public class MarketDataMarksClient {

  private static final Logger log = LoggerFactory.getLogger(MarketDataMarksClient.class);
  private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
      new ParameterizedTypeReference<>() {};

  private final RestClient rest;

  public MarketDataMarksClient(
      @Value("${market-data.base-url:http://market-data:8080}") String baseUrl) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(1));
    factory.setReadTimeout(Duration.ofSeconds(1));
    this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
  }

  /** Marks for the given COMPACT OCCs, or null when market-data could not be read. */
  public MarksResponse marks(List<String> compactOccs) {
    try {
      Map<String, Object> body =
          rest.get()
              .uri(b -> b.path("/md/marks").queryParam("occ", compactOccs.toArray()).build())
              .retrieve()
              .body(MAP_TYPE);
      return body == null ? null : parse(body);
    } catch (RuntimeException e) {
      log.warn("market-data marks read failed: {}", e.getMessage());
      return null;
    }
  }

  /** Wire seam (scope-lock shape); null on a shape it does not recognise. */
  static MarksResponse parse(Map<String, Object> body) {
    if (!(body.get("marks") instanceof List<?> rows)) {
      return null;
    }
    List<Map<String, Object>> marks = new ArrayList<>();
    for (Object row : rows) {
      if (row instanceof Map<?, ?> m && m.get("occ") instanceof String) {
        Map<String, Object> copy = new LinkedHashMap<>();
        m.forEach((k, v) -> copy.put(String.valueOf(k), v));
        marks.add(copy);
      }
    }
    return new MarksResponse(body.get("now") instanceof String n ? n : null, marks);
  }

  /**
   * @param now market-data's own clock when it answered (quote ages are relative to it)
   * @param marks raw mark rows keyed by compact {@code occ}
   */
  public record MarksResponse(String now, List<Map<String, Object>> marks) {}
}
