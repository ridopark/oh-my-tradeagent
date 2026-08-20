package com.ohmytradeagent.tdbff.proximity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MarketDataLivenessClientTest {

  /**
   * The WIRE CONTRACT with market-data's {@code /md/premium-subscriptions}. Every other test in
   * this change mocks this client, so a renamed key would leave them all green while the badge
   * reported "unknown" for the rest of time. These literals are pinned from the producing side by
   * MarketDataQuoteControllerTest; the pair must be edited together.
   */
  @Test
  void parsesTheExactShapeMarketDataEmits() {
    Map<String, Object> body =
        Map.of(
            "now",
            "2026-08-20T14:00:10Z",
            "subscriptions",
            List.of(
                Map.of(
                    "occ",
                    "DRAM  270319C00100000",
                    "subscribers",
                    1,
                    "poll_ok_count",
                    4210,
                    "last_poll_ok_at",
                    "2026-08-20T14:00:09.500Z",
                    "last_emit_at",
                    "2026-08-20T13:52:01.100Z",
                    "consecutive_failures",
                    0)));

    MarketDataLivenessClient.PremiumSubscriptions out =
        MarketDataLivenessClient.parsePremiumSubscriptions(body);

    assertThat(out).isNotNull();
    assertThat(out.now()).isEqualTo("2026-08-20T14:00:10Z");
    assertThat(out.byOcc()).containsKey("DRAM  270319C00100000");
    assertThat(out.byOcc().get("DRAM  270319C00100000"))
        .containsEntry("last_poll_ok_at", "2026-08-20T14:00:09.500Z");
  }

  /** An empty registry is a POSITIVE statement (nothing subscribed), never a parse failure. */
  @Test
  void emptyRegistryParsesToAnEmptyMap_notNull() {
    MarketDataLivenessClient.PremiumSubscriptions out =
        MarketDataLivenessClient.parsePremiumSubscriptions(
            Map.of("now", "2026-08-20T14:00:10Z", "subscriptions", List.of()));

    assertThat(out).isNotNull();
    assertThat(out.byOcc()).isEmpty();
  }

  /** An unrecognised shape must be null (-> "unknown"), never a confidently empty registry. */
  @Test
  void unrecognisedShapeIsNull_soItRendersUnknownNotOrphaned() {
    assertThat(MarketDataLivenessClient.parsePremiumSubscriptions(Map.of("now", "x"))).isNull();
  }

  @Test
  void unreachableMarketData_premiumSubscriptionsIsNull_notEmpty() {
    // Null and empty mean different things: empty = market-data is up and holds no subscription
    // (the #717 signal); null = we could not ask. Port 1 is not listening.
    MarketDataLivenessClient client = new MarketDataLivenessClient("http://localhost:1");
    assertThat(client.premiumSubscriptions()).isNull();
  }

  @Test
  void unreachableMarketData_degradesToUnknown_notThrow() {
    // Port 1 is not listening: connection is refused fast, so feedHealth must fail soft.
    MarketDataLivenessClient client = new MarketDataLivenessClient("http://localhost:1");
    Map<String, Object> out = client.feedHealth();
    assertThat(out).containsEntry("status", "unknown");
  }
}
