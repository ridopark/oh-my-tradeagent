package com.ohmytradeagent.tdbff.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The OCC normalization is the load-bearing join key between a tracked position's PADDED canonical
 * OCC and the broker marks' COMPACT OCC. A drift here silently drops every mark (empty join), so it
 * is asserted directly.
 */
class BrokerPositionsClientTest {

  @Test
  void compactOcc_stripsAllWhitespace_soPaddedAndCompactCollide() {
    assertThat(BrokerPositionsClient.compactOcc("SPY   260519C00737000"))
        .isEqualTo("SPY260519C00737000");
    assertThat(BrokerPositionsClient.compactOcc("SPY260519C00737000"))
        .isEqualTo("SPY260519C00737000");
    // Same contract, two forms -> identical key.
    assertThat(BrokerPositionsClient.compactOcc("AMZN  260724C00260000"))
        .isEqualTo(BrokerPositionsClient.compactOcc("AMZN260724C00260000"));
  }

  @Test
  void compactOcc_nullOrBlank_returnsNull() {
    assertThat(BrokerPositionsClient.compactOcc(null)).isNull();
    assertThat(BrokerPositionsClient.compactOcc("   ")).isNull();
  }
}
