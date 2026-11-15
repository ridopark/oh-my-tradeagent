package com.ohmytradeagent.marketdata.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Drives {@link AlpacaResearchClient} against two in-process {@link MockWebServer}s (data host and
 * trading host) and asserts both the Alpaca request it sends and the contract shape it returns.
 */
class AlpacaResearchClientTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private MockWebServer dataServer;
  private MockWebServer tradingServer;
  private AlpacaResearchClient client;

  @BeforeEach
  void start() throws IOException {
    dataServer = new MockWebServer();
    dataServer.start();
    tradingServer = new MockWebServer();
    tradingServer.start();
    client = new AlpacaResearchClient(restClient(dataServer), restClient(tradingServer));
  }

  @AfterEach
  void stop() throws IOException {
    dataServer.shutdown();
    tradingServer.shutdown();
  }

  private static RestClient restClient(MockWebServer server) {
    return RestClient.builder()
        .baseUrl(server.url("/").toString().replaceAll("/$", ""))
        .defaultHeader("APCA-API-KEY-ID", "key-id-for-test")
        .build();
  }

  private static MockResponse json(String body) {
    return new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body);
  }

  /** The client's result as it will be serialized, for path-style assertions. */
  private JsonNode tree(Map<String, Object> result) {
    return mapper.valueToTree(result);
  }

  @Test
  void snapshot_shapesTradeQuoteBarsAndDayChange() throws Exception {
    dataServer.enqueue(
        json(
            "{\"symbol\":\"NVDA\","
                + "\"latestTrade\":{\"p\":142.5,\"t\":\"2026-11-13T20:59:59Z\",\"s\":100},"
                + "\"latestQuote\":{\"bp\":142.4,\"ap\":142.6,\"t\":\"2026-11-13T20:59:58Z\"},"
                + "\"dailyBar\":{\"t\":\"2026-11-13T05:00:00Z\",\"o\":140.0,\"h\":143.0,"
                + "\"l\":139.5,\"c\":142.5,\"v\":1000000,\"vw\":141.2,\"n\":5000},"
                + "\"prevDailyBar\":{\"t\":\"2026-11-12T05:00:00Z\",\"o\":138.0,\"h\":141.0,"
                + "\"l\":137.0,\"c\":140.0,\"v\":900000,\"vw\":139.1,\"n\":4000}}"));

    JsonNode out = tree(client.snapshot("NVDA"));

    assertThat(out.get("ticker").asText()).isEqualTo("NVDA");
    assertThat(out.get("price").asDouble()).isEqualTo(142.5);
    assertThat(out.get("trade_time").asText()).isEqualTo("2026-11-13T20:59:59Z");
    assertThat(out.get("bid").asDouble()).isEqualTo(142.4);
    assertThat(out.get("ask").asDouble()).isEqualTo(142.6);
    assertThat(out.get("quote_time").asText()).isEqualTo("2026-11-13T20:59:58Z");
    assertThat(out.get("day").get("date").asText()).isEqualTo("2026-11-13T05:00:00Z");
    assertThat(out.get("day").get("open").asDouble()).isEqualTo(140.0);
    assertThat(out.get("day").get("high").asDouble()).isEqualTo(143.0);
    assertThat(out.get("day").get("low").asDouble()).isEqualTo(139.5);
    assertThat(out.get("day").get("close").asDouble()).isEqualTo(142.5);
    assertThat(out.get("day").get("volume").asLong()).isEqualTo(1000000L);
    assertThat(out.get("day").get("vwap").asDouble()).isEqualTo(141.2);
    assertThat(out.get("day").has("n")).isFalse();
    assertThat(out.get("prev_day").get("close").asDouble()).isEqualTo(140.0);
    assertThat(out.get("day_change").decimalValue()).isEqualByComparingTo("2.5");
    assertThat(out.get("day_change_pct").decimalValue()).isEqualByComparingTo("1.79");

    RecordedRequest req = dataServer.takeRequest();
    assertThat(req.getPath()).isEqualTo("/v2/stocks/NVDA/snapshot?feed=sip");
    assertThat(req.getHeader("APCA-API-KEY-ID")).isEqualTo("key-id-for-test");
  }

  @Test
  void snapshot_missingPieces_areNullNotInvented() {
    dataServer.enqueue(json("{\"symbol\":\"NVDA\",\"latestTrade\":{\"p\":142.5}}"));

    JsonNode out = tree(client.snapshot("NVDA"));

    assertThat(out.get("price").asDouble()).isEqualTo(142.5);
    for (String field :
        new String[] {
          "trade_time",
          "bid",
          "ask",
          "quote_time",
          "day",
          "prev_day",
          "day_change",
          "day_change_pct"
        }) {
      assertThat(out.has(field)).as(field).isTrue();
      assertThat(out.get(field).isNull()).as(field).isTrue();
    }
  }

  @Test
  void snapshot_callerInputIsEncodedNotInterpreted() throws Exception {
    dataServer.enqueue(json("{}"));

    client.snapshot("../x?feed=iex");

    assertThat(dataServer.takeRequest().getPath())
        .isEqualTo("/v2/stocks/..%2Fx%3Ffeed%3Diex/snapshot?feed=sip");
  }

  @Test
  void bars_mapsVwToVwapAndFlagsTruncation() throws Exception {
    dataServer.enqueue(
        json(
            "{\"bars\":[{\"t\":\"2026-11-13T14:30:00Z\",\"o\":1.0,\"h\":2.0,\"l\":0.5,\"c\":1.5,"
                + "\"v\":100,\"vw\":1.2,\"n\":7}],\"symbol\":\"SPY\","
                + "\"next_page_token\":\"abc\"}"));

    JsonNode out = tree(client.bars("SPY", "5Min", "2026-11-13", "2026-11-14", 500));

    assertThat(out.get("ticker").asText()).isEqualTo("SPY");
    assertThat(out.get("timeframe").asText()).isEqualTo("5Min");
    assertThat(out.get("truncated").asBoolean()).isTrue();
    JsonNode bar = out.get("bars").get(0);
    assertThat(bar.get("t").asText()).isEqualTo("2026-11-13T14:30:00Z");
    assertThat(bar.get("o").asDouble()).isEqualTo(1.0);
    assertThat(bar.get("h").asDouble()).isEqualTo(2.0);
    assertThat(bar.get("l").asDouble()).isEqualTo(0.5);
    assertThat(bar.get("c").asDouble()).isEqualTo(1.5);
    assertThat(bar.get("v").asLong()).isEqualTo(100L);
    assertThat(bar.get("vwap").asDouble()).isEqualTo(1.2);
    assertThat(bar.get("n").asLong()).isEqualTo(7L);
    assertThat(bar.has("vw")).isFalse();

    assertThat(dataServer.takeRequest().getPath())
        .isEqualTo(
            "/v2/stocks/SPY/bars?timeframe=5Min&start=2026-11-13&end=2026-11-14&limit=500"
                + "&adjustment=all&feed=sip");
  }

  @Test
  void bars_noEnd_omitsParam_nullTokenAndNullBarsMeanNotTruncatedAndEmpty() throws Exception {
    dataServer.enqueue(json("{\"bars\":null,\"symbol\":\"SPY\",\"next_page_token\":null}"));

    JsonNode out = tree(client.bars("SPY", "1Day", "2026-11-13T14:30:00Z", null, 10));

    assertThat(out.get("truncated").asBoolean()).isFalse();
    assertThat(out.get("bars")).isEmpty();
    String path = dataServer.takeRequest().getPath();
    assertThat(path).doesNotContain("end=");
    assertThat(path).contains("start=2026-11-13T14%3A30%3A00Z");
  }

  @Test
  void news_dropsContentImagesAndUpdatedAt() throws Exception {
    dataServer.enqueue(
        json(
            "{\"news\":[{\"id\":42,\"headline\":\"H\",\"summary\":\"S\",\"source\":\"benzinga\","
                + "\"author\":\"A\",\"created_at\":\"2026-11-13T12:00:00Z\","
                + "\"updated_at\":\"2026-11-13T12:05:00Z\",\"url\":\"https://example.com/n\","
                + "\"content\":\"<p>long</p>\",\"images\":[{\"url\":\"x\"}],"
                + "\"symbols\":[\"NVDA\",\"AMD\"]}],\"next_page_token\":null}"));

    JsonNode out = tree(client.news("NVDA,AMD", "2026-11-01", 10));

    JsonNode item = out.get("news").get(0);
    assertThat(item.get("id").asLong()).isEqualTo(42L);
    assertThat(item.get("headline").asText()).isEqualTo("H");
    assertThat(item.get("summary").asText()).isEqualTo("S");
    assertThat(item.get("source").asText()).isEqualTo("benzinga");
    assertThat(item.get("author").asText()).isEqualTo("A");
    assertThat(item.get("created_at").asText()).isEqualTo("2026-11-13T12:00:00Z");
    assertThat(item.get("url").asText()).isEqualTo("https://example.com/n");
    assertThat(item.get("symbols").get(1).asText()).isEqualTo("AMD");
    assertThat(item.has("content")).isFalse();
    assertThat(item.has("images")).isFalse();
    assertThat(item.has("updated_at")).isFalse();

    assertThat(dataServer.takeRequest().getPath())
        .isEqualTo("/v1beta1/news?symbols=NVDA%2CAMD&start=2026-11-01&limit=10");
  }

  @Test
  void news_noSymbolsNoStart_sendsOnlyLimit() throws Exception {
    dataServer.enqueue(json("{\"news\":[]}"));

    client.news(null, null, 10);

    assertThat(dataServer.takeRequest().getPath()).isEqualTo("/v1beta1/news?limit=10");
  }

  @Test
  void movers_mergesMoversAndMostActives() throws Exception {
    dataServer.enqueue(
        json(
            "{\"gainers\":[{\"symbol\":\"AAA\",\"price\":5.5,\"change\":1.5,"
                + "\"percent_change\":37.5}],"
                + "\"losers\":[{\"symbol\":\"BBB\",\"price\":2.0,\"change\":-1.0,"
                + "\"percent_change\":-33.3}],"
                + "\"market_type\":\"stocks\",\"last_updated\":\"2026-11-13T21:00:00Z\"}"));
    dataServer.enqueue(
        json(
            "{\"most_actives\":[{\"symbol\":\"CCC\",\"volume\":123456,\"trade_count\":789}],"
                + "\"last_updated\":\"2026-11-13T21:00:01Z\"}"));

    JsonNode out = tree(client.movers(10));

    assertThat(out.get("last_updated").asText()).isEqualTo("2026-11-13T21:00:00Z");
    JsonNode gainer = out.get("gainers").get(0);
    assertThat(gainer.get("symbol").asText()).isEqualTo("AAA");
    assertThat(gainer.get("price").asDouble()).isEqualTo(5.5);
    assertThat(gainer.get("change").asDouble()).isEqualTo(1.5);
    assertThat(gainer.get("percent_change").asDouble()).isEqualTo(37.5);
    assertThat(out.get("losers").get(0).get("symbol").asText()).isEqualTo("BBB");
    JsonNode active = out.get("most_active").get(0);
    assertThat(active.get("symbol").asText()).isEqualTo("CCC");
    assertThat(active.get("volume").asLong()).isEqualTo(123456L);
    assertThat(active.get("trade_count").asLong()).isEqualTo(789L);
    assertThat(out.has("market_type")).isFalse();

    assertThat(dataServer.takeRequest().getPath())
        .isEqualTo("/v1beta1/screener/stocks/movers?top=10");
    assertThat(dataServer.takeRequest().getPath())
        .isEqualTo("/v1beta1/screener/stocks/most-actives?top=10&by=volume");
  }

  @Test
  void clock_readsTheTradingHost() throws Exception {
    tradingServer.enqueue(
        json(
            "{\"timestamp\":\"2026-11-15T10:00:00-05:00\",\"is_open\":false,"
                + "\"next_open\":\"2026-11-16T09:30:00-05:00\","
                + "\"next_close\":\"2026-11-16T16:00:00-05:00\"}"));

    JsonNode out = tree(client.clock());

    assertThat(out.get("is_open").asBoolean()).isFalse();
    assertThat(out.get("next_open").asText()).isEqualTo("2026-11-16T09:30:00-05:00");
    assertThat(out.get("next_close").asText()).isEqualTo("2026-11-16T16:00:00-05:00");
    assertThat(out.get("timestamp").asText()).isEqualTo("2026-11-15T10:00:00-05:00");
    assertThat(tradingServer.takeRequest().getPath()).isEqualTo("/v2/clock");
    assertThat(dataServer.getRequestCount()).isZero();
  }

  @Test
  void corporateActions_passesAlpacaObjectThrough() throws Exception {
    dataServer.enqueue(
        json(
            "{\"corporate_actions\":{\"forward_splits\":[{\"symbol\":\"NVDA\",\"new_rate\":10,"
                + "\"old_rate\":1,\"ex_date\":\"2024-06-10\"}]},\"next_page_token\":null}"));

    JsonNode out = tree(client.corporateActions("NVDA", "2024-01-01", null));

    assertThat(out.get("symbol").asText()).isEqualTo("NVDA");
    JsonNode split = out.get("corporate_actions").get("forward_splits").get(0);
    assertThat(split.get("new_rate").asInt()).isEqualTo(10);
    assertThat(split.get("ex_date").asText()).isEqualTo("2024-06-10");
    assertThat(out.has("next_page_token")).isFalse();

    assertThat(dataServer.takeRequest().getPath())
        .isEqualTo("/v1/corporate-actions?symbols=NVDA&start=2024-01-01&limit=100");
  }

  @Test
  void corporateActions_withEnd_sendsEnd() throws Exception {
    dataServer.enqueue(json("{\"corporate_actions\":{}}"));

    client.corporateActions("NVDA", "2024-01-01", "2024-12-31");

    assertThat(dataServer.takeRequest().getPath())
        .isEqualTo("/v1/corporate-actions?symbols=NVDA&start=2024-01-01&end=2024-12-31&limit=100");
  }

  @Test
  void openInterest_followsPagesAndParsesStringOpenInterest() throws Exception {
    tradingServer.enqueue(
        json(
            "{\"option_contracts\":["
                + "{\"symbol\":\"NVDA261120C00140000\",\"open_interest\":\"1234\","
                + "\"open_interest_date\":\"2026-11-13\"},"
                + "{\"symbol\":\"NVDA261120C00145000\",\"open_interest\":null,"
                + "\"open_interest_date\":null}],\"next_page_token\":\"tok 2\"}"));
    tradingServer.enqueue(
        json(
            "{\"option_contracts\":[{\"symbol\":\"NVDA261120C00150000\"}],"
                + "\"next_page_token\":null}"));

    JsonNode out = tree(client.openInterest("NVDA", "2026-11-20", "C"));

    assertThat(out.get("underlying").asText()).isEqualTo("NVDA");
    assertThat(out.get("expiration").asText()).isEqualTo("2026-11-20");
    assertThat(out.get("right").asText()).isEqualTo("C");
    JsonNode contracts = out.get("contracts");
    assertThat(contracts).hasSize(3);
    assertThat(contracts.get("NVDA261120C00140000").get("open_interest").isNumber()).isTrue();
    assertThat(contracts.get("NVDA261120C00140000").get("open_interest").asInt()).isEqualTo(1234);
    assertThat(contracts.get("NVDA261120C00140000").get("open_interest_date").asText())
        .isEqualTo("2026-11-13");
    assertThat(contracts.get("NVDA261120C00145000").get("open_interest").isNull()).isTrue();
    assertThat(contracts.get("NVDA261120C00145000").get("open_interest_date").isNull()).isTrue();
    assertThat(contracts.get("NVDA261120C00150000").get("open_interest").isNull()).isTrue();

    assertThat(tradingServer.takeRequest().getPath())
        .isEqualTo(
            "/v2/options/contracts?underlying_symbols=NVDA&expiration_date=2026-11-20&type=call"
                + "&status=active&limit=1000");
    assertThat(tradingServer.takeRequest().getPath())
        .isEqualTo(
            "/v2/options/contracts?underlying_symbols=NVDA&expiration_date=2026-11-20&type=call"
                + "&status=active&limit=1000&page_token=tok%202");
    assertThat(dataServer.getRequestCount()).isZero();
  }

  @Test
  void openInterest_put_stopsAfterFivePages() throws Exception {
    for (int i = 0; i < 6; i++) {
      tradingServer.enqueue(
          json(
              "{\"option_contracts\":[{\"symbol\":\"SPY261120P0050"
                  + i
                  + "000\",\"open_interest\":\"1\"}],\"next_page_token\":\"t"
                  + i
                  + "\"}"));
    }

    JsonNode out = tree(client.openInterest("SPY", "2026-11-20", "P"));

    assertThat(out.get("contracts")).hasSize(5);
    assertThat(tradingServer.getRequestCount()).isEqualTo(5);
    assertThat(tradingServer.takeRequest().getPath()).contains("type=put");
  }

  @Test
  void upstreamErrorStatus_throwsResearchUpstreamException() {
    dataServer.enqueue(new MockResponse().setResponseCode(500).setBody("{\"message\":\"boom\"}"));

    assertThatThrownBy(() -> client.snapshot("NVDA")).isInstanceOf(ResearchUpstreamException.class);
  }

  @Test
  void emptyBody_throwsResearchUpstreamException() {
    tradingServer.enqueue(new MockResponse().setResponseCode(200));

    assertThatThrownBy(() -> client.clock()).isInstanceOf(ResearchUpstreamException.class);
  }

  @Test
  void unreadableBody_throwsResearchUpstreamException() {
    dataServer.enqueue(json("not json"));

    assertThatThrownBy(() -> client.movers(10)).isInstanceOf(ResearchUpstreamException.class);
  }

  @Test
  void connectionFailure_throwsResearchUpstreamException() throws Exception {
    tradingServer.shutdown();

    assertThatThrownBy(() -> client.clock()).isInstanceOf(ResearchUpstreamException.class);
  }
}
