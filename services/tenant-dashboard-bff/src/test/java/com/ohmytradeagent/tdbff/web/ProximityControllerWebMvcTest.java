package com.ohmytradeagent.tdbff.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.proximity.MarketDataLivenessClient;
import com.ohmytradeagent.tdbff.proximity.MarketDataQuoteClient;
import com.ohmytradeagent.tdbff.proximity.ProximityReader;
import com.ohmytradeagent.tdbff.proximity.ProximityReader.PositionProximity;
import com.ohmytradeagent.tdbff.proximity.ProximityReader.WatchlistProximity;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Web-layer coverage for {@code /api/proximity}: tenant gate + response shape. */
@WebMvcTest(ProximityController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
class ProximityControllerWebMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private ProximityReader reader;
  @MockitoBean private MarketDataLivenessClient liveness;
  @MockitoBean private MarketDataQuoteClient quotes;

  @Test
  void missingTenantHeaderIs401() throws Exception {
    mvc.perform(get("/api/proximity"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("missing_tenant"));
  }

  @Test
  void returnsTenantScopedProximityWithLiveness() throws Exception {
    when(liveness.feedHealth()).thenReturn(Map.of("status", "ok"));
    when(quotes.equityPrice("NVDA")).thenReturn(new BigDecimal("142.30"));
    when(reader.watchlist("acme"))
        .thenReturn(
            List.of(
                new WatchlistProximity(
                    "wf-leg",
                    "wl",
                    "NVDA",
                    "ABOVE",
                    new BigDecimal("761.00"),
                    new BigDecimal("757.195"),
                    new BigDecimal("764.805"),
                    new BigDecimal("760.50"),
                    "ARMED",
                    0.0657)));
    when(reader.positions("acme"))
        .thenReturn(
            List.of(
                new PositionProximity(
                    "wf-pos",
                    "wl",
                    "NVDA  260516C00140000",
                    new BigDecimal("2.00"),
                    new BigDecimal("1.50"),
                    new BigDecimal("3.00"),
                    new BigDecimal("2.40"),
                    null,
                    false,
                    37.5,
                    25.0)));

    mvc.perform(get("/api/proximity").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenant_id").value("acme"))
        .andExpect(jsonPath("$.liveness.status").value("ok"))
        .andExpect(jsonPath("$.watchlist[0].ticker").value("NVDA"))
        .andExpect(jsonPath("$.watchlist[0].distance_to_trigger_pct").value(0.0657))
        .andExpect(jsonPath("$.positions[0].distance_to_stop_pct").value(37.5))
        .andExpect(jsonPath("$.positions[0].distance_to_target_pct").value(25.0))
        .andExpect(jsonPath("$.positions[0].underlying").value("NVDA"))
        .andExpect(jsonPath("$.positions[0].underlying_price").value(142.30));
  }
}
