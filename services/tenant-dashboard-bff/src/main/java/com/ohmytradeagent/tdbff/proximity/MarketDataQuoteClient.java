package com.ohmytradeagent.tdbff.proximity;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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

  /**
   * The /live chain picker's reads. A chain is one or more pages of Alpaca snapshots (and the
   * expirations probe adds a spot read in front), so the 2s budget the per-contract reads use would
   * time out a large chain. Still bounded: an operator is waiting on it.
   */
  private final RestClient chainRest;

  public MarketDataQuoteClient(
      @Value("${market-data.base-url:http://market-data:8080}") String baseUrl) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(2));
    factory.setReadTimeout(Duration.ofSeconds(2));
    this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    SimpleClientHttpRequestFactory chainFactory = new SimpleClientHttpRequestFactory();
    chainFactory.setConnectTimeout(Duration.ofSeconds(2));
    chainFactory.setReadTimeout(Duration.ofSeconds(8));
    this.chainRest = RestClient.builder().baseUrl(baseUrl).requestFactory(chainFactory).build();
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

  /**
   * Listed expirations for {@code underlying} from today on (ascending) with the spot they were
   * probed around, or null when they could not be read. market-data 503s on a provider failure, so
   * an empty list here is a real "no listed options", never an outage.
   */
  public ChainExpirations optionExpirations(String underlying) {
    try {
      Map<String, Object> body =
          chainRest.get().uri("/md/chain/{u}/expirations", underlying).retrieve().body(MAP_TYPE);
      return parseExpirations(body);
    } catch (RuntimeException e) {
      log.warn("market-data chain expirations read failed for {}: {}", underlying, e.getMessage());
      return null;
    }
  }

  /**
   * One side ({@code right} = "C" or "P") of one expiry for {@code underlying}, strike ascending,
   * or null when it could not be read (market-data 503s on a provider failure, which lands here).
   */
  public List<ChainRow> optionChain(String underlying, LocalDate expiration, String right) {
    try {
      Map<String, Object> body =
          chainRest
              .get()
              .uri(
                  u ->
                      u.path("/md/chain/{u}")
                          .queryParam("expiration", expiration.toString())
                          .queryParam("right", right)
                          .build(underlying))
              .retrieve()
              .body(MAP_TYPE);
      return parseChain(body);
    } catch (RuntimeException e) {
      log.warn(
          "market-data chain read failed for {} {} {}: {}",
          underlying,
          expiration,
          right,
          e.getMessage());
      return null;
    }
  }

  /**
   * 1-minute bars (high/low/close) for {@code ticker} in {@code [start, end)}, oldest first, or
   * null when they could not be read (market-data 503s on a provider failure). An empty list is a
   * real "no bars yet".
   */
  public List<EquityBar> equityBars1m(String ticker, Instant start, Instant end) {
    try {
      Map<String, Object> body =
          chainRest
              .get()
              .uri(
                  u ->
                      u.path("/md/equity/{t}/bars1m")
                          .queryParam("start", start.toString())
                          .queryParam("end", end.toString())
                          .build(ticker))
              .retrieve()
              .body(MAP_TYPE);
      return parseBars(body);
    } catch (RuntimeException e) {
      log.warn("market-data bars read failed for {}: {}", ticker, e.getMessage());
      return null;
    }
  }

  /** The {@code /md/equity/{t}/bars1m} wire shape; null when it is not that shape. */
  static List<EquityBar> parseBars(Map<String, Object> body) {
    if (body == null || !(body.get("bars") instanceof List<?> raw)) {
      return null;
    }
    return raw.stream()
        .map(r -> (Map<?, ?>) r)
        .map(
            r ->
                new EquityBar(
                    Instant.parse(String.valueOf(r.get("t"))),
                    decimal(r.get("h")),
                    decimal(r.get("l")),
                    decimal(r.get("c"))))
        .toList();
  }

  /** The {@code /md/chain/{u}/expirations} wire shape; null when it is not that shape. */
  static ChainExpirations parseExpirations(Map<String, Object> body) {
    if (body == null || !(body.get("expirations") instanceof List<?> raw)) {
      return null;
    }
    return new ChainExpirations(
        decimal(body.get("spot")), raw.stream().map(d -> LocalDate.parse(d.toString())).toList());
  }

  /** The {@code /md/chain/{u}} wire shape; null when it is not that shape. */
  static List<ChainRow> parseChain(Map<String, Object> body) {
    if (body == null || !(body.get("contracts") instanceof List<?> raw)) {
      return null;
    }
    return raw.stream()
        .map(r -> (Map<?, ?>) r)
        .map(
            r ->
                new ChainRow(
                    String.valueOf(r.get("occ")),
                    decimal(r.get("strike")),
                    decimal(r.get("bid")),
                    decimal(r.get("mid")),
                    decimal(r.get("ask")),
                    decimal(r.get("iv")),
                    decimal(r.get("delta")),
                    r.get("volume") == null ? null : Long.valueOf(r.get("volume").toString())))
        .toList();
  }

  private static BigDecimal decimal(Object raw) {
    return raw == null ? null : new BigDecimal(raw.toString());
  }

  /** One NBBO snapshot. Any field may be null when the provider omitted it. */
  public record OptionQuote(BigDecimal bid, BigDecimal mid, BigDecimal ask) {}

  /** One 1-minute bar's range. */
  public record EquityBar(Instant t, BigDecimal high, BigDecimal low, BigDecimal close) {}

  /** Listed expirations, ascending, and the spot market-data probed them around (may be null). */
  public record ChainExpirations(BigDecimal spot, List<LocalDate> expirations) {}

  /**
   * One chain row ({@code occ} is COMPACT). Market fields may be null when the provider omitted
   * them.
   */
  public record ChainRow(
      String occ,
      BigDecimal strike,
      BigDecimal bid,
      BigDecimal mid,
      BigDecimal ask,
      BigDecimal iv,
      BigDecimal delta,
      Long volume) {}
}
