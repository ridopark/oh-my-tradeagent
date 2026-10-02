package com.ohmytradeagent.tdbff.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.tdbff.promotion.LivePromotionReader;
import com.ohmytradeagent.tdbff.promotion.LivePromotionReader.PromotionStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The severity ranking behind the /live activation banner. A tenant can hold several live
 * strategies in different states, and the banner renders ONE verdict — so getting this order wrong
 * would show "expires soon" on a tenant whose orders are already being refused.
 */
class LivePromotionControllerOverallTest {

  private static PromotionStatus s(String status) {
    return new PromotionStatus("copytrade-v1", "alpaca-live", status, null, null);
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
    // Deactivation and a risk-relevant config change void the approval just as expiry does.
    assertThat(LivePromotionController.overall(List.of(s("active"), s("deactivated"))))
        .isEqualTo("deactivated");
    assertThat(LivePromotionController.overall(List.of(s("expiring"), s("config_changed"))))
        .isEqualTo("config_changed");
    assertThat(LivePromotionController.overall(List.of(s("unknown"), s("config_changed"))))
        .isEqualTo("config_changed");
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

  @Test
  void enumerationFailure_isUnknown_notNone() {
    // "none" renders no banner — an all-clear. A tenant whose strategies we cannot even list is
    // unverified, not paper-only.
    LivePromotionReader reader = mock(LivePromotionReader.class);
    TenantContext ctx = mock(TenantContext.class);
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(ctx.tenantId(req)).thenReturn("prod_real");
    when(reader.statuses(any(), any())).thenThrow(new RuntimeException("connection refused"));

    Map<String, Object> body = new LivePromotionController(reader, ctx).get(req).getBody();

    assertThat(body).containsEntry("overall", "unknown").containsEntry("strategies", List.of());
  }
}
