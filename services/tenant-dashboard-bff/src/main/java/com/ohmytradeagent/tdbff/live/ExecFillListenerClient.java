package com.ohmytradeagent.tdbff.live;

import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reads exec's {@code GET /status/fill-listener} for one broker_target. A read-only status probe —
 * not the broker trading API. Throws on any failure; {@link LiveConnectionService} turns that into
 * {@code status:"unknown"}.
 */
@Component
public class ExecFillListenerClient {

  private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
      new ParameterizedTypeReference<>() {};

  private final Map<String, RestClient> byBrokerTarget;

  public ExecFillListenerClient(
      @Value("${exec.base-url.alpaca-paper:http://exec-alpaca-paper:8080}") String paperUrl,
      @Value("${exec.base-url.alpaca-live:http://exec-alpaca-live:8080}") String liveUrl) {
    this.byBrokerTarget = Map.of("alpaca-paper", client(paperUrl), "alpaca-live", client(liveUrl));
  }

  private static RestClient client(String baseUrl) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofMillis(1500));
    factory.setReadTimeout(Duration.ofMillis(1500));
    return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
  }

  public Map<String, Object> status(String brokerTarget) {
    RestClient rest = byBrokerTarget.get(brokerTarget);
    if (rest == null) {
      throw new IllegalArgumentException("no exec base-url for broker_target " + brokerTarget);
    }
    Map<String, Object> body = rest.get().uri("/status/fill-listener").retrieve().body(MAP_TYPE);
    if (body == null) {
      throw new IllegalStateException("empty fill-listener status");
    }
    return body;
  }
}
