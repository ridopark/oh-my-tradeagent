package com.ohmytradeagent.marketdata.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.ohmytradeagent.marketdata.provider.alpaca.AlpacaMarketDataProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

/**
 * Read-only Alpaca reads behind {@code /md/research/*} (market research for the MCP surface). Each
 * method makes the Alpaca call(s) named in the research contract and reshapes the body; a field
 * Alpaca did not send is {@code null} in the result, never invented.
 *
 * <p>Uses its OWN RestClients, deliberately not the {@code alpacaMarketDataRestClient} bean: that
 * one's 1.5s timeout is tuned for the account kill-switch quote read (see {@code
 * AlpacaMarketDataConfig}) and must not be shared with slower, lower-priority research reads.
 *
 * <p>Caller input only ever reaches Alpaca as a URI template variable, so it is always encoded.
 */
@Component
@ConditionalOnProperty(name = "market-data.provider", havingValue = "alpaca")
public class AlpacaResearchClient {

  private static final Logger log = LoggerFactory.getLogger(AlpacaResearchClient.class);
  private static final int MAX_OPEN_INTEREST_PAGES = 5;

  private final RestClient data;
  private final RestClient trading;

  @Autowired
  public AlpacaResearchClient(
      AlpacaMarketDataProperties props,
      @Value("${market-data.alpaca.trading-base-url:https://api.alpaca.markets}")
          String tradingBaseUrl,
      RestClient.Builder builder) {
    this(
        restClient(builder, props.dataBaseUrl(), props),
        restClient(builder, tradingBaseUrl, props));
  }

  AlpacaResearchClient(RestClient data, RestClient trading) {
    this.data = data;
    this.trading = trading;
  }

  private static RestClient restClient(
      RestClient.Builder builder, String baseUrl, AlpacaMarketDataProperties props) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(2));
    requestFactory.setReadTimeout(Duration.ofSeconds(6));
    return builder
        .clone()
        .baseUrl(baseUrl)
        .requestFactory(requestFactory)
        .defaultHeader("APCA-API-KEY-ID", props.apiKeyId())
        .defaultHeader("APCA-API-SECRET-KEY", props.apiSecretKey())
        .defaultHeader("Accept", "application/json")
        .build();
  }

  public Map<String, Object> snapshot(String ticker) {
    JsonNode body =
        get(
            data,
            "snapshot",
            b -> b.path("/v2/stocks/{s}/snapshot").queryParam("feed", "sip").build(ticker));
    JsonNode trade = val(body, "latestTrade");
    JsonNode quote = val(body, "latestQuote");
    JsonNode day = val(body, "dailyBar");
    JsonNode prev = val(body, "prevDailyBar");

    BigDecimal change = null;
    BigDecimal changePct = null;
    JsonNode close = val(day, "c");
    JsonNode prevClose = val(prev, "c");
    if (close != null && close.isNumber() && prevClose != null && prevClose.isNumber()) {
      change = close.decimalValue().subtract(prevClose.decimalValue());
      if (prevClose.decimalValue().signum() != 0) {
        changePct =
            change
                .multiply(BigDecimal.valueOf(100))
                .divide(prevClose.decimalValue(), 2, RoundingMode.HALF_UP);
      }
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("ticker", ticker);
    out.put("price", val(trade, "p"));
    out.put("trade_time", val(trade, "t"));
    out.put("bid", val(quote, "bp"));
    out.put("ask", val(quote, "ap"));
    out.put("quote_time", val(quote, "t"));
    out.put("day", dailyBar(day));
    out.put("prev_day", dailyBar(prev));
    out.put("day_change", change);
    out.put("day_change_pct", changePct);
    return out;
  }

  private static Map<String, Object> dailyBar(JsonNode bar) {
    if (bar == null) {
      return null;
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("date", val(bar, "t"));
    out.put("open", val(bar, "o"));
    out.put("high", val(bar, "h"));
    out.put("low", val(bar, "l"));
    out.put("close", val(bar, "c"));
    out.put("volume", val(bar, "v"));
    out.put("vwap", val(bar, "vw"));
    return out;
  }

  /** One page of bars; {@code end} may be null. */
  public Map<String, Object> bars(
      String ticker, String timeframe, String start, String end, int limit) {
    Map<String, Object> vars = new LinkedHashMap<>();
    vars.put("s", ticker);
    vars.put("timeframe", timeframe);
    vars.put("start", start);
    vars.put("end", end);
    vars.put("limit", limit);
    JsonNode body =
        get(
            data,
            "bars",
            b -> {
              b.path("/v2/stocks/{s}/bars")
                  .queryParam("timeframe", "{timeframe}")
                  .queryParam("start", "{start}");
              if (end != null) {
                b.queryParam("end", "{end}");
              }
              return b.queryParam("limit", "{limit}")
                  .queryParam("adjustment", "all")
                  .queryParam("feed", "sip")
                  .build(vars);
            });
    List<Map<String, Object>> bars = new ArrayList<>();
    for (JsonNode bar : array(body, "bars")) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("t", val(bar, "t"));
      row.put("o", val(bar, "o"));
      row.put("h", val(bar, "h"));
      row.put("l", val(bar, "l"));
      row.put("c", val(bar, "c"));
      row.put("v", val(bar, "v"));
      row.put("vwap", val(bar, "vw"));
      row.put("n", val(bar, "n"));
      bars.add(row);
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("ticker", ticker);
    out.put("timeframe", timeframe);
    out.put("bars", bars);
    out.put("truncated", val(body, "next_page_token") != null);
    return out;
  }

  /** {@code symbols} and {@code start} may be null. */
  public Map<String, Object> news(String symbols, String start, int limit) {
    Map<String, Object> vars = new LinkedHashMap<>();
    vars.put("symbols", symbols);
    vars.put("start", start);
    vars.put("limit", limit);
    JsonNode body =
        get(
            data,
            "news",
            b -> {
              b.path("/v1beta1/news");
              if (symbols != null) {
                b.queryParam("symbols", "{symbols}");
              }
              if (start != null) {
                b.queryParam("start", "{start}");
              }
              return b.queryParam("limit", "{limit}").build(vars);
            });
    List<Map<String, Object>> news = new ArrayList<>();
    for (JsonNode item : array(body, "news")) {
      news.add(
          pick(
              item,
              "id",
              "headline",
              "summary",
              "source",
              "author",
              "created_at",
              "url",
              "symbols"));
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("news", news);
    return out;
  }

  public Map<String, Object> movers(int top) {
    JsonNode movers =
        get(
            data,
            "movers",
            b -> b.path("/v1beta1/screener/stocks/movers").queryParam("top", "{top}").build(top));
    JsonNode actives =
        get(
            data,
            "most-actives",
            b ->
                b.path("/v1beta1/screener/stocks/most-actives")
                    .queryParam("top", "{top}")
                    .queryParam("by", "volume")
                    .build(top));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("last_updated", val(movers, "last_updated"));
    out.put("gainers", movers(movers, "gainers"));
    out.put("losers", movers(movers, "losers"));
    List<Map<String, Object>> mostActive = new ArrayList<>();
    for (JsonNode row : array(actives, "most_actives")) {
      mostActive.add(pick(row, "symbol", "volume", "trade_count"));
    }
    out.put("most_active", mostActive);
    return out;
  }

  private static List<Map<String, Object>> movers(JsonNode body, String field) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (JsonNode row : array(body, field)) {
      out.add(pick(row, "symbol", "price", "change", "percent_change"));
    }
    return out;
  }

  public Map<String, Object> clock() {
    JsonNode body = get(trading, "clock", b -> b.path("/v2/clock").build());
    return pick(body, "is_open", "next_open", "next_close", "timestamp");
  }

  /** {@code end} may be null. */
  public Map<String, Object> corporateActions(String symbol, String start, String end) {
    Map<String, Object> vars = new LinkedHashMap<>();
    vars.put("symbols", symbol);
    vars.put("start", start);
    vars.put("end", end);
    JsonNode body =
        get(
            data,
            "corporate-actions",
            b -> {
              b.path("/v1/corporate-actions")
                  .queryParam("symbols", "{symbols}")
                  .queryParam("start", "{start}");
              if (end != null) {
                b.queryParam("end", "{end}");
              }
              return b.queryParam("limit", 100).build(vars);
            });
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("symbol", symbol);
    out.put("corporate_actions", val(body, "corporate_actions"));
    return out;
  }

  /**
   * @param right {@code C} or {@code P}
   */
  public Map<String, Object> openInterest(String underlying, String expiration, String right) {
    Map<String, Object> vars = new LinkedHashMap<>();
    vars.put("underlying", underlying);
    vars.put("expiration", expiration);
    vars.put("type", "C".equals(right) ? "call" : "put");
    Map<String, Object> contracts = new LinkedHashMap<>();
    String pageToken = null;
    for (int page = 0; page < MAX_OPEN_INTEREST_PAGES; page++) {
      vars.put("pageToken", pageToken);
      boolean hasToken = pageToken != null;
      JsonNode body =
          get(
              trading,
              "open-interest",
              b -> {
                b.path("/v2/options/contracts")
                    .queryParam("underlying_symbols", "{underlying}")
                    .queryParam("expiration_date", "{expiration}")
                    .queryParam("type", "{type}")
                    .queryParam("status", "active")
                    .queryParam("limit", 1000);
                if (hasToken) {
                  b.queryParam("page_token", "{pageToken}");
                }
                return b.build(vars);
              });
      for (JsonNode contract : array(body, "option_contracts")) {
        JsonNode symbol = val(contract, "symbol");
        if (symbol == null) {
          continue;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("open_interest", number(val(contract, "open_interest")));
        row.put("open_interest_date", val(contract, "open_interest_date"));
        contracts.put(symbol.asText(), row);
      }
      JsonNode next = val(body, "next_page_token");
      if (next == null || next.asText().isEmpty()) {
        break;
      }
      pageToken = next.asText();
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("underlying", underlying);
    out.put("expiration", expiration);
    out.put("right", right);
    out.put("contracts", contracts);
    return out;
  }

  private JsonNode get(RestClient client, String route, Function<UriBuilder, URI> uri) {
    JsonNode body;
    try {
      body = client.get().uri(uri).retrieve().body(JsonNode.class);
    } catch (RestClientException e) {
      // Status / exception type only: never the message (it carries the full request URI).
      Object status =
          e instanceof RestClientResponseException r
              ? r.getStatusCode().value()
              : e.getClass().getSimpleName();
      log.warn("alpaca research read failed route={} status={}", route, status);
      throw new ResearchUpstreamException(route);
    }
    if (body == null || body.isNull()) {
      log.warn("alpaca research read failed route={} status=empty_body", route);
      throw new ResearchUpstreamException(route);
    }
    return body;
  }

  /** The field's value, or null when the parent or field is absent or JSON null. */
  private static JsonNode val(JsonNode node, String field) {
    JsonNode v = node == null ? null : node.get(field);
    return v == null || v.isNull() ? null : v;
  }

  private static Iterable<JsonNode> array(JsonNode node, String field) {
    JsonNode v = val(node, field);
    return v != null && v.isArray() ? v : List.of();
  }

  private static Map<String, Object> pick(JsonNode node, String... fields) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (String field : fields) {
      out.put(field, val(node, field));
    }
    return out;
  }

  /** Alpaca sends open interest as a string; anything unparseable is null. */
  private static BigDecimal number(JsonNode node) {
    if (node == null) {
      return null;
    }
    try {
      return new BigDecimal(node.asText());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
