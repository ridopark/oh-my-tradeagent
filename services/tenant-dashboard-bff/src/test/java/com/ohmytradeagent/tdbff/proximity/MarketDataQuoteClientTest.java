package com.ohmytradeagent.tdbff.proximity;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MarketDataQuoteClientTest {

  /**
   * The WIRE CONTRACT with market-data's {@code /md/chain/{u}}. Every controller test mocks this
   * client, so a renamed key would leave them green while the chain picker showed blank prices.
   * These literals are pinned from the producing side by MarketDataQuoteControllerTest; the pair
   * must be edited together.
   */
  @Test
  void parseChain_readsTheExactShapeMarketDataEmits_includingNullFields() {
    Map<String, Object> quoted = new HashMap<>();
    quoted.put("occ", "NVDA261016P00180000");
    quoted.put("strike", 180);
    quoted.put("bid", 2.10);
    quoted.put("mid", 2.15);
    quoted.put("ask", 2.20);
    quoted.put("iv", 0.44);
    quoted.put("delta", -0.41);
    quoted.put("volume", 1200);
    Map<String, Object> unquoted = new HashMap<>(quoted);
    unquoted.put("occ", "NVDA261016P00100000");
    unquoted.put("bid", null);
    unquoted.put("mid", null);
    unquoted.put("ask", null);
    unquoted.put("volume", null);

    List<MarketDataQuoteClient.ChainRow> rows =
        MarketDataQuoteClient.parseChain(Map.of("contracts", List.of(quoted, unquoted)));

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).occ()).isEqualTo("NVDA261016P00180000");
    assertThat(rows.get(0).strike()).isEqualByComparingTo("180");
    assertThat(rows.get(0).ask()).isEqualByComparingTo("2.20");
    assertThat(rows.get(0).iv()).isEqualByComparingTo("0.44");
    assertThat(rows.get(0).delta()).isEqualByComparingTo("-0.41");
    assertThat(rows.get(0).volume()).isEqualTo(1200L);
    assertThat(rows.get(1).ask()).isNull();
    assertThat(rows.get(1).volume()).isNull();
  }

  @Test
  void parseChain_unrecognisedShape_isNull_notAnEmptyChain() {
    assertThat(MarketDataQuoteClient.parseChain(Map.of("rows", List.of()))).isNull();
  }

  @Test
  void parseExpirations_readsSpotAndIsoDates() {
    MarketDataQuoteClient.ChainExpirations read =
        MarketDataQuoteClient.parseExpirations(
            Map.of("spot", 182.4, "expirations", List.of("2026-10-16", "2026-10-23")));
    assertThat(read.spot()).isEqualByComparingTo("182.4");
    assertThat(read.expirations())
        .containsExactly(LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 23));
    assertThat(MarketDataQuoteClient.parseExpirations(Map.of())).isNull();
  }

  /**
   * The outage path end to end on the BFF side: market-data answers a provider failure with 503
   * (pinned by MarketDataQuoteControllerTest), and that must reach the controller as null — its
   * "chain_unavailable" — never as an empty chain the picker would show as "nothing listed".
   */
  @Test
  void marketData503_isNull_notAnEmptyChainOrExpirationList() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          byte[] body = "{\"error\":\"chain_unavailable\"}".getBytes();
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(503, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      MarketDataQuoteClient client =
          new MarketDataQuoteClient("http://127.0.0.1:" + server.getAddress().getPort());
      assertThat(client.optionChain("NVDA", LocalDate.of(2026, 10, 16), "C")).isNull();
      assertThat(client.optionExpirations("NVDA")).isNull();
    } finally {
      server.stop(0);
    }
  }
}
