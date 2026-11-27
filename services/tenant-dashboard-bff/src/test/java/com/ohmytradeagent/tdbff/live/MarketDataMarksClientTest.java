package com.ohmytradeagent.tdbff.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Wire seam for market-data {@code GET /md/marks} (scope-lock shape). */
class MarketDataMarksClientTest {

  @Test
  void parsesNowAndMarkRows() {
    Map<String, Object> body =
        Map.of(
            "now",
            "2026-10-01T14:00:01Z",
            "marks",
            List.of(Map.of("occ", "SPY260519C00737000", "bid", 1.25, "warming", false)));

    MarketDataMarksClient.MarksResponse r = MarketDataMarksClient.parse(body);

    assertThat(r.now()).isEqualTo("2026-10-01T14:00:01Z");
    assertThat(r.marks()).hasSize(1);
    assertThat(r.marks().get(0)).containsEntry("occ", "SPY260519C00737000");
    assertThat(r.marks().get(0)).containsEntry("bid", 1.25);
  }

  /** A null polled_at (never polled) stays a present null; absent would mean "old market-data". */
  @Test
  void nullPolledAtKeyIsKept() {
    Map<String, Object> row = new HashMap<>();
    row.put("occ", "SPY260519C00737000");
    row.put("polled_at", null);
    Map<String, Object> body = Map.of("now", "2026-10-01T14:00:01Z", "marks", List.of(row));

    Map<String, Object> mark = MarketDataMarksClient.parse(body).marks().get(0);

    assertThat(mark).containsKey("polled_at");
    assertThat(mark.get("polled_at")).isNull();
  }

  @Test
  void rowWithoutOccIsSkipped() {
    Map<String, Object> body =
        Map.of("now", "2026-10-01T14:00:01Z", "marks", List.of(Map.of("bid", 1.0)));

    assertThat(MarketDataMarksClient.parse(body).marks()).isEmpty();
  }

  @Test
  void unrecognisedShapeIsNull_soItRendersAsUnreachableNotAsNoMarks() {
    assertThat(MarketDataMarksClient.parse(Map.of("now", "x"))).isNull();
  }

  /** What actually goes on the wire, against a real local HTTP server. */
  private static String queryFor(
      java.util.function.BiFunction<MarketDataMarksClient, List<String>, ?> call) throws Exception {
    java.util.concurrent.atomic.AtomicReference<String> seen =
        new java.util.concurrent.atomic.AtomicReference<>();
    com.sun.net.httpserver.HttpServer server =
        com.sun.net.httpserver.HttpServer.create(
            new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/md/marks",
        ex -> {
          seen.set(ex.getRequestURI().getRawQuery());
          byte[] body = "{\"now\":\"x\",\"marks\":[]}".getBytes();
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(200, body.length);
          ex.getResponseBody().write(body);
          ex.close();
        });
    server.start();
    try {
      call.apply(
          new MarketDataMarksClient("http://127.0.0.1:" + server.getAddress().getPort()),
          List.of("SPY260519C00737000"));
      return seen.get();
    } finally {
      server.stop(0);
    }
  }

  @Test
  void marks_sendsNoLane_soOperatorsKeepTheirOwnBudget() throws Exception {
    assertThat(queryFor(MarketDataMarksClient::marks)).isEqualTo("occ=SPY260519C00737000");
  }

  @Test
  void demoMarks_asksForTheDemoLane() throws Exception {
    assertThat(queryFor(MarketDataMarksClient::demoMarks))
        .isEqualTo("occ=SPY260519C00737000&lane=demo");
  }
}
