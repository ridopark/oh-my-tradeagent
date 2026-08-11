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
      Map<String, Object> body = rest.get().uri("/md/equity/{t}", ticker).retrieve().body(MAP_TYPE);
      Object price = body == null ? null : body.get("price");
      return price == null ? null : new BigDecimal(price.toString());
    } catch (RuntimeException e) {
      log.warn("market-data equityquote read failed for {}: {}", ticker, e.getMessage());
      return null;
    }
  }

  /**
   * Indicative option premium (mid) for {@code occ}, or null when unavailable. The OCC is compacted
   * (spaces stripped) so the canonical padded form is a valid path segment.
   */
  public BigDecimal optionPremium(String occ) {
    OptionQuote quote = optionQuote(occ);
    return quote == null ? null : quote.mid();
  }

  /**
   * PLAN-2026-08-10-live-manual-bto: the full NBBO snapshot for {@code occ}, or null when
   * unavailable. {@link #optionPremium} has always thrown away the bid/ask the endpoint returns;
   * the manual-entry confirm step needs all three (the operator sees the spread, and the ASK is
   * what anchors the marketable limit).
   *
   * <p>Individual fields may still be null when the provider omits them. The manual-entry path
   * treats a null quote — or a null ask — as a hard refusal rather than pricing an order blind;
   * that is the CALLER's decision, so this method keeps the fail-soft null contract the proximity
   * read depends on.
   *
   * <p>Backed by an on-demand REST snapshot ({@code AlpacaMarketData.snapshotQuote}), so it works
   * for a contract the tenant does not hold and needs no premium subscription.
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

  private static BigDecimal decimal(Object raw) {
    return raw == null ? null : new BigDecimal(raw.toString());
  }

  /** One NBBO snapshot. Any field may be null when the provider omitted it. */
  public record OptionQuote(BigDecimal bid, BigDecimal mid, BigDecimal ask) {}
}
