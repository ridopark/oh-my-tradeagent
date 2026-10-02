package com.ohmytradeagent.tdbff.live;

import static org.assertj.core.api.Assertions.assertThat;

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
}
