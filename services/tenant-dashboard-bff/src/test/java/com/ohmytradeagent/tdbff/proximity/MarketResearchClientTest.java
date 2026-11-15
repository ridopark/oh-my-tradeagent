package com.ohmytradeagent.tdbff.proximity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ohmytradeagent.tdbff.proximity.MarketResearchClient.RateLimitedException;
import com.ohmytradeagent.tdbff.proximity.MarketResearchClient.UnavailableException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The BFF's read of market-data's {@code /md/research/*}: URI shape and failure mapping. */
class MarketResearchClientTest {

  private static final String BASE = "http://market-data:8080";

  private MockRestServiceServer marketData;
  private MarketResearchClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
    marketData = MockRestServiceServer.bindTo(builder).build();
    client = new MarketResearchClient(builder.build());
  }

  @Test
  void ok_passesBodyThrough_withParamsInTheQuery() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("ticker", "NVDA");
    params.put("timeframe", "5Min");
    marketData
        .expect(requestTo(BASE + "/md/research/bars?ticker=NVDA&timeframe=5Min"))
        .andRespond(withSuccess("{\"ticker\":\"NVDA\",\"bars\":[]}", MediaType.APPLICATION_JSON));

    assertThat(client.get("bars", params)).containsEntry("ticker", "NVDA").containsKey("bars");
    marketData.verify();
  }

  @Test
  void noParams_sendsNoQuery() {
    marketData
        .expect(requestTo(BASE + "/md/research/clock"))
        .andRespond(withSuccess("{\"is_open\":true}", MediaType.APPLICATION_JSON));

    assertThat(client.get("clock", Map.of())).containsEntry("is_open", true);
  }

  @Test
  void values_areEncodedAsData_neverAsQueryOrPathSyntax() {
    // Defence in depth behind the controller's validation: a value carrying query/path syntax must
    // arrive as ONE encoded value, not as an extra parameter or segment.
    marketData
        .expect(requestTo(BASE + "/md/research/news?symbols=A%26feed%3Diex%2F..%2Fx%2Bb%2CAMD"))
        .andRespond(withSuccess("{\"news\":[]}", MediaType.APPLICATION_JSON));

    client.get("news", Map.of("symbols", "A&feed=iex/../x+b,AMD"));
    marketData.verify();
  }

  @Test
  void upstream429_isRateLimited() {
    marketData
        .expect(requestTo(BASE + "/md/research/movers"))
        .andRespond(
            withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"rate_limited\"}"));

    assertThatThrownBy(() -> client.get("movers", Map.of()))
        .isInstanceOf(RateLimitedException.class);
  }

  @Test
  void upstream503_isUnavailable() {
    marketData
        .expect(requestTo(BASE + "/md/research/movers"))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

    assertThatThrownBy(() -> client.get("movers", Map.of()))
        .isInstanceOf(UnavailableException.class);
  }

  @Test
  void upstream404_isUnavailable() {
    // market-data only serves /md/research/* when its provider is alpaca; elsewhere it is a 404.
    marketData
        .expect(requestTo(BASE + "/md/research/clock"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    assertThatThrownBy(() -> client.get("clock", Map.of()))
        .isInstanceOf(UnavailableException.class);
  }

  @Test
  void timeoutOrUnreachable_isUnavailable() {
    marketData
        .expect(requestTo(BASE + "/md/research/clock"))
        .andRespond(
            request -> {
              throw new IOException("Read timed out");
            });

    assertThatThrownBy(() -> client.get("clock", Map.of()))
        .isInstanceOf(UnavailableException.class);
  }

  @Test
  void emptyBody_isUnavailable() {
    marketData
        .expect(requestTo(BASE + "/md/research/clock"))
        .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client.get("clock", Map.of()))
        .isInstanceOf(UnavailableException.class);
  }
}
