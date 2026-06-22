package com.ohmytradeagent.tdbff.proximity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MarketDataLivenessClientTest {

  @Test
  void unreachableMarketData_degradesToUnknown_notThrow() {
    // Port 1 is not listening: connection is refused fast, so feedHealth must fail soft.
    MarketDataLivenessClient client = new MarketDataLivenessClient("http://localhost:1");
    Map<String, Object> out = client.feedHealth();
    assertThat(out).containsEntry("status", "unknown");
  }
}
