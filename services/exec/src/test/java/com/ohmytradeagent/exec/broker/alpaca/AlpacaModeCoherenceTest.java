package com.ohmytradeagent.exec.broker.alpaca;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Pins the Alpaca fail-fast guards extracted VERBATIM from the pre-P4-a {@code
 * AlpacaConfig.alpacaRestClient} bean into {@link AlpacaModeCoherence}: blank-cred fail-fast,
 * live-must-not-target-paper-baseUrl, paper-must-target-paper-baseUrl, live-ws-must-not-be-paper.
 * Same {@link IllegalStateException} messages.
 */
class AlpacaModeCoherenceTest {

  private static final String LIVE_HOST = "https://api.alpaca.markets";
  private static final String PAPER_HOST = "https://paper-api.alpaca.markets";
  private static final String LIVE_WS = "wss://api.alpaca.markets/stream";
  private static final String PAPER_WS = "wss://paper-api.alpaca.markets/stream";

  @Test
  void blankApiKeyFailsFast() {
    assertThatThrownBy(() -> AlpacaModeCoherence.assertCredentialsPresent("", "secret"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("APCA_API_KEY_ID");
  }

  @Test
  void blankApiSecretFailsFast() {
    assertThatThrownBy(() -> AlpacaModeCoherence.assertCredentialsPresent("key", "  "))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("APCA_API_SECRET_KEY");
  }

  @Test
  void presentCredsPass() {
    assertThatCode(() -> AlpacaModeCoherence.assertCredentialsPresent("key", "secret"))
        .doesNotThrowAnyException();
  }

  @Test
  void liveImplPointedAtPaperHostFailsFast() {
    assertThatThrownBy(() -> AlpacaModeCoherence.assertCoherent("alpaca-live", PAPER_HOST, LIVE_WS))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("alpaca-live")
        .hasMessageContaining("paper endpoint");
  }

  @Test
  void paperImplPointedAtLiveHostFailsFast() {
    assertThatThrownBy(
            () -> AlpacaModeCoherence.assertCoherent("alpaca-paper", LIVE_HOST, PAPER_WS))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("alpaca-paper")
        .hasMessageContaining("paper endpoint");
  }

  @Test
  void liveImplWithPaperWsUrlFailsFast() {
    assertThatThrownBy(() -> AlpacaModeCoherence.assertCoherent("alpaca-live", LIVE_HOST, PAPER_WS))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("fill-listener");
  }

  @Test
  void liveImplCoherentPasses() {
    assertThatCode(() -> AlpacaModeCoherence.assertCoherent("alpaca-live", LIVE_HOST, LIVE_WS))
        .doesNotThrowAnyException();
  }

  @Test
  void paperImplCoherentPasses() {
    assertThatCode(() -> AlpacaModeCoherence.assertCoherent("alpaca-paper", PAPER_HOST, PAPER_WS))
        .doesNotThrowAnyException();
  }

  @Test
  void paperImplWithBlankWsUrlPasses() {
    // A paper/stub build may keep the default (blank) ws-url; the live-only WS guard must not break
    // it.
    assertThatCode(() -> AlpacaModeCoherence.assertCoherent("alpaca-paper", PAPER_HOST, ""))
        .doesNotThrowAnyException();
  }
}
