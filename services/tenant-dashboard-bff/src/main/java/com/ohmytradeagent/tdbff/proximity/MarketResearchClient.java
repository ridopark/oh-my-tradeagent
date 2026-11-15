package com.ohmytradeagent.tdbff.proximity;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Reads the market-data worker's {@code /md/research/*} routes (snapshot, bars, news, movers,
 * clock, corporate actions, open interest) and hands the JSON body back untouched. Unlike {@link
 * MarketDataQuoteClient} this is NOT fail-soft: the caller is a research read with nothing to
 * degrade to, so a rate limit and an outage are reported as two distinct exceptions.
 */
@Component
public class MarketResearchClient {

  private static final Logger log = LoggerFactory.getLogger(MarketResearchClient.class);
  private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
      new ParameterizedTypeReference<>() {};

  private final RestClient rest;

  @Autowired
  public MarketResearchClient(
      @Value("${market-data.base-url:http://market-data:8080}") String baseUrl) {
    this(RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory()).build());
  }

  MarketResearchClient(RestClient rest) {
    this.rest = rest;
  }

  private static SimpleClientHttpRequestFactory requestFactory() {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(2));
    factory.setReadTimeout(Duration.ofSeconds(8));
    return factory;
  }

  /**
   * GET {@code /md/research/{route}} with {@code params} as the query string. Every value travels
   * as a URI template variable, so it is percent-encoded as data and can never add a parameter or a
   * path segment.
   *
   * @throws RateLimitedException when market-data answered 429
   * @throws UnavailableException on any other failure (non-2xx, timeout, unreachable, empty body)
   */
  public Map<String, Object> get(String route, Map<String, String> params) {
    Map<String, Object> body;
    try {
      body =
          rest.get()
              .uri(
                  u -> {
                    u.path("/md/research/{route}");
                    Map<String, Object> vars = new LinkedHashMap<>(params);
                    vars.put("route", route);
                    params.keySet().forEach(name -> u.queryParam(name, "{" + name + "}"));
                    return u.build(vars);
                  })
              .retrieve()
              .body(MAP_TYPE);
    } catch (HttpClientErrorException.TooManyRequests e) {
      throw new RateLimitedException();
    } catch (RuntimeException e) {
      log.warn("market-data research read failed for {}: {}", route, e.getMessage());
      throw new UnavailableException();
    }
    if (body == null) {
      log.warn("market-data research read for {} returned no body", route);
      throw new UnavailableException();
    }
    return body;
  }

  /** market-data's own limiter refused the read. */
  public static class RateLimitedException extends RuntimeException {}

  /** market-data (or the provider behind it) could not answer. */
  public static class UnavailableException extends RuntimeException {}
}
