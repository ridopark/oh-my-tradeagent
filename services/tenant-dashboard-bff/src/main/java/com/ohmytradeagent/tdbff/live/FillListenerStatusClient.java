package com.ohmytradeagent.tdbff.live;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reads a tenant's broker fill-listener status through api-gateway's read-only {@code GET
 * /internal/live/fill-listener-status?tenant=} (which routes to the exec pod serving the tenant's
 * broker_target). The BFF never calls exec directly: exec's NetworkPolicy admits only api-gateway.
 *
 * <p>Returns api-gateway's 200 body as-is. A non-2xx answer, or a missing service token, becomes
 * {@code {status:"unknown", reason}} for {@link LiveConnectionService#brokerPart}; a transport
 * failure throws and the caller's part fallback turns it into {@code unknown}.
 */
@Component
public class FillListenerStatusClient {

  private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
      new ParameterizedTypeReference<>() {};

  private final RestClient rest;
  private final String serviceToken;

  @Autowired
  public FillListenerStatusClient(
      @Value("${api-gateway.base-url:http://api-gateway:8082}") String baseUrl,
      @Value("${api-gateway.service-token:}") String serviceToken) {
    this(boundedClient(baseUrl), serviceToken);
  }

  FillListenerStatusClient(RestClient rest, String serviceToken) {
    this.rest = rest;
    this.serviceToken = serviceToken;
  }

  /** Under the BFF's 2s part timeout; api-gateway's own exec hop is bounded at 1.5s. */
  private static RestClient boundedClient(String baseUrl) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofMillis(1000));
    factory.setReadTimeout(Duration.ofMillis(1900));
    return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
  }

  public Map<String, Object> status(String tenantId) {
    if (serviceToken == null || serviceToken.isBlank()) {
      return unknown("api-gateway service token not configured");
    }
    return rest.get()
        .uri(
            b ->
                b.path("/internal/live/fill-listener-status")
                    .queryParam("tenant", tenantId)
                    .build())
        .header("Authorization", "Bearer " + serviceToken)
        .exchange(
            (req, res) -> {
              Map<String, Object> body = res.bodyTo(MAP_TYPE);
              if (res.getStatusCode().is2xxSuccessful()) {
                if (body == null) {
                  throw new IllegalStateException("empty fill-listener status");
                }
                return body;
              }
              String reason =
                  body != null && body.get("reason") instanceof String r
                      ? "api-gateway: " + r
                      : "api-gateway HTTP " + res.getStatusCode().value();
              return unknown(reason);
            });
  }

  private static Map<String, Object> unknown(String reason) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("status", "unknown");
    m.put("reason", reason);
    return m;
  }
}
