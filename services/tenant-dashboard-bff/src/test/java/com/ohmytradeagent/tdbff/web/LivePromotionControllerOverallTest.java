package com.ohmytradeagent.tdbff.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.tdbff.promotion.LivePromotionReader.PromotionStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The severity ranking behind the /live activation banner. A tenant can hold several live
 * strategies in different states, and the banner renders ONE verdict — so getting this order wrong
 * would show "expires soon" on a tenant whose orders are already being refused.
 */
class LivePromotionControllerOverallTest {

  private static PromotionStatus s(String status) {
    return new PromotionStatus("copytrade-v1", "alpaca-live", status, null, null, null, null);
  }

  @Test
  void noLiveStrategies_isNone() {
    assertThat(LivePromotionController.overall(List.of())).isEqualTo("none");
  }

  @Test
  void allActive_isActive() {
    assertThat(LivePromotionController.overall(List.of(s("active"), s("active"))))
        .isEqualTo("active");
  }

  @Test
  void blockingStatusesOutrankEverythingElse() {
    // A healthy watchlist strategy must not soften a copytrade strategy that is being refused.
    assertThat(LivePromotionController.overall(List.of(s("active"), s("stale"))))
        .isEqualTo("stale");
    assertThat(LivePromotionController.overall(List.of(s("expiring"), s("absent"))))
        .isEqualTo("absent");
    assertThat(LivePromotionController.overall(List.of(s("unknown"), s("stale"))))
        .isEqualTo("stale");
  }

  @Test
  void unknownOutranksExpiring() {
    // An all-clear we cannot verify is worse than a deadline we can see coming.
    assertThat(LivePromotionController.overall(List.of(s("expiring"), s("unknown"))))
        .isEqualTo("unknown");
  }

  @Test
  void expiringOutranksActive() {
    assertThat(LivePromotionController.overall(List.of(s("active"), s("expiring"))))
        .isEqualTo("expiring");
  }
}
