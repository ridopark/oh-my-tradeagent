package com.ohmytradeagent.orchestrator.alert.floorbreach;

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
 * Issue #779: reads the NBBO snapshot for one OCC from the market-data worker's {@code
 * /md/option/{occ}} endpoint. Copied from the BFF's {@code proximity/MarketDataQuoteClient}
 * (deliberately NOT shared — the two services have no common module for this and ~40 lines beats a
 * new dependency).
 *
 * <p>Fail-soft, never throws: market-data is a separate deployment, so a timeout / unreachable
 * worker / malformed body degrades to {@code null}, which the floor-breach evaluator maps to
 * UNKNOWN (never "all clear", never an alert). Short timeouts so an unreachable worker cannot pin
 * the scheduler thread.
 */
@Component
public class MarketDataOptionQuoteClient {

  private static final Logger log = LoggerFactory.getLogger(MarketDataOptionQuoteClient.class);
  private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
      new ParameterizedTypeReference<>() {};

  private final RestClient rest;

  public MarketDataOptionQuoteClient(
      @Value("${market-data.base-url:http://market-data:8080}") String baseUrl) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(2));
    factory.setReadTimeout(Duration.ofSeconds(2));
    this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
  }

  /**
   * The full NBBO snapshot for {@code occ} (compacted — spaces stripped so the canonical padded
   * form is a valid path segment), or {@code null} when unavailable. Individual fields may still be
   * null when the provider omits them; note the snapshot is UNFILTERED by the #690 tick guard, so
   * the caller must treat a no-bid quote as untrustworthy, not as worthless.
   */
  public OptionQuote optionQuote(String occ) {
    String compact = occ.replace(" ", "");
    try {
      Map<String, Object> body =
          rest.get().uri("/md/option/{o}", compact).retrieve().body(MAP_TYPE);
      if (body == null) {
        return null;
      }
      return new OptionQuote(
          decimal(body.get("bid")), decimal(body.get("mid")), decimal(body.get("ask")));
    } catch (RuntimeException e) {
      log.warn("market-data optionquote read failed for {}: {}", compact, e.getMessage());
      return null;
    }
  }

  /**
   * The IV + greeks snapshot for {@code occ} from the market-data worker's {@code
   * /md/option/{occ}/greeks} endpoint (#783), or {@code null} when unavailable. Individual fields
   * may still be null when the provider omits them. Read once per entry observation by the
   * trade-context recorder — never on the per-poll hot path.
   */
  public OptionGreeksSnapshot optionGreeks(String occ) {
    String compact = occ.replace(" ", "");
    try {
      Map<String, Object> body =
          rest.get().uri("/md/option/{o}/greeks", compact).retrieve().body(MAP_TYPE);
      if (body == null) {
        return null;
      }
      return new OptionGreeksSnapshot(
          decimal(body.get("iv")),
          decimal(body.get("delta")),
          decimal(body.get("gamma")),
          decimal(body.get("theta")),
          decimal(body.get("vega")));
    } catch (RuntimeException e) {
      log.warn("market-data greeks read failed for {}: {}", compact, e.getMessage());
      return null;
    }
  }

  /**
   * The underlying's last-trade price from {@code /md/equity/{ticker}} (#783), or {@code null} when
   * unavailable. Same fail-soft contract as the option reads.
   */
  public BigDecimal underlyingSpot(String ticker) {
    try {
      Map<String, Object> body = rest.get().uri("/md/equity/{t}", ticker).retrieve().body(MAP_TYPE);
      return body == null ? null : decimal(body.get("price"));
    } catch (RuntimeException e) {
      log.warn("market-data equity read failed for {}: {}", ticker, e.getMessage());
      return null;
    }
  }

  private static BigDecimal decimal(Object raw) {
    return raw == null ? null : new BigDecimal(raw.toString());
  }

  /** One NBBO snapshot. Any field may be null when the provider omitted it. */
  public record OptionQuote(BigDecimal bid, BigDecimal mid, BigDecimal ask) {}

  /** One IV + greeks snapshot (#783). Any field may be null when the provider omitted it. */
  public record OptionGreeksSnapshot(
      BigDecimal iv, BigDecimal delta, BigDecimal gamma, BigDecimal theta, BigDecimal vega) {}
}
