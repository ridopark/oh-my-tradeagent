package com.ohmytradeagent.tdbff.live;

import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reads the Discord sidecar's {@code GET /healthz} ({@code {"heartbeat_age_s": num|null}}). The
 * heartbeat means "watch loop alive", not "Discord readable". Throws on any failure; {@link
 * LiveConnectionService} turns that into {@code status:"unknown"}.
 */
@Component
public class DiscordHealthClient {

  private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
      new ParameterizedTypeReference<>() {};

  private final RestClient rest;

  public DiscordHealthClient(
      @Value("${signal-source-discord.base-url:http://signal-source-discord:8090}")
          String baseUrl) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofMillis(1500));
    factory.setReadTimeout(Duration.ofMillis(1500));
    this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
  }

  public Map<String, Object> health() {
    Map<String, Object> body = rest.get().uri("/healthz").retrieve().body(MAP_TYPE);
    if (body == null) {
      throw new IllegalStateException("empty healthz body");
    }
    return body;
  }
}
