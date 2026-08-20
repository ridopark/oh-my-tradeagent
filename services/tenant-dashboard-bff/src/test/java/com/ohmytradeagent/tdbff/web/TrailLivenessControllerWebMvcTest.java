package com.ohmytradeagent.tdbff.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.positions.PositionsReader;
import com.ohmytradeagent.tdbff.positions.PositionsReader.OpenPosition;
import com.ohmytradeagent.tdbff.proximity.MarketDataLivenessClient;
import com.ohmytradeagent.tdbff.proximity.MarketDataLivenessClient.PremiumSubscriptions;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code /api/trail-liveness} three-state contract (#717). The distinction these tests exist to
 * hold is {@code orphaned} vs {@code unknown}: an operator's response to "orphaned" is to re-arm a
 * stop on a real-money position, so a market-data outage must never be able to render as one.
 */
@WebMvcTest(TrailLivenessController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
class TrailLivenessControllerWebMvcTest {

  private static final String OCC = "DRAM  270319C00100000";

  @Autowired private MockMvc mvc;
  @MockitoBean private PositionsReader reader;
  @MockitoBean private MarketDataLivenessClient liveness;

  private static OpenPosition armed() {
    return new OpenPosition(
        "wf1",
        "s-copytrade-v1",
        OCC,
        2,
        new BigDecimal("3.28"),
        new BigDecimal("656"),
        true,
        new BigDecimal("0.45"),
        new BigDecimal("2.43375"),
        101L,
        OffsetDateTime.parse("2026-08-19T18:14:50.916Z"));
  }

  private static PremiumSubscriptions subs(String now, String lastPollOkAt) {
    return new PremiumSubscriptions(
        now, Map.of(OCC, Map.of("occ", OCC, "subscribers", 1, "last_poll_ok_at", lastPollOkAt)));
  }

  @Test
  void missingTenantHeaderIs401() throws Exception {
    mvc.perform(get("/api/trail-liveness")).andExpect(status().isUnauthorized());
  }

  @Test
  void freshPoll_isLive_andCarriesTheTickCounterThePulseReads() throws Exception {
    when(reader.openPositions("acme")).thenReturn(List.of(armed()));
    when(liveness.premiumSubscriptions())
        .thenReturn(subs("2026-08-20T14:00:10Z", "2026-08-20T14:00:09.500Z"));

    mvc.perform(get("/api/trail-liveness").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.market_data_reachable").value(true))
        .andExpect(jsonPath("$.positions[0].feed_status").value("live"))
        .andExpect(jsonPath("$.positions[0].trailing_armed").value(true))
        .andExpect(jsonPath("$.positions[0].ticks_received").value(101));
  }

  /**
   * market-data is up and has never heard of this contract — the #717 orphan, stated positively.
   */
  @Test
  void contractAbsentFromRegistry_isOrphaned() throws Exception {
    when(reader.openPositions("acme")).thenReturn(List.of(armed()));
    when(liveness.premiumSubscriptions())
        .thenReturn(new PremiumSubscriptions("2026-08-20T14:00:10Z", Map.of()));

    mvc.perform(get("/api/trail-liveness").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.market_data_reachable").value(true))
        .andExpect(jsonPath("$.positions[0].feed_status").value("orphaned"));
  }

  /** Subscribed, but the poll clock has stopped: 20 missed polls at 500ms is dead, not quiet. */
  @Test
  void staleLastPoll_isOrphaned() throws Exception {
    when(reader.openPositions("acme")).thenReturn(List.of(armed()));
    when(liveness.premiumSubscriptions())
        .thenReturn(subs("2026-08-20T14:00:10Z", "2026-08-20T13:59:00Z"));

    mvc.perform(get("/api/trail-liveness").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.positions[0].feed_status").value("orphaned"));
  }

  /**
   * THE safety case. market-data unreachable must degrade to "unknown" — never to "orphaned", which
   * would send an operator to re-arm a stop on a live position because a monitoring hop failed.
   */
  @Test
  void marketDataUnreachable_isUnknown_neverOrphaned() throws Exception {
    when(reader.openPositions("acme")).thenReturn(List.of(armed()));
    when(liveness.premiumSubscriptions()).thenReturn(null);

    mvc.perform(get("/api/trail-liveness").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.market_data_reachable").value(false))
        .andExpect(jsonPath("$.positions[0].feed_status").value("unknown"));
  }
}
