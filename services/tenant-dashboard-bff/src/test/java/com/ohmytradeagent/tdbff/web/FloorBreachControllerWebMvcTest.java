package com.ohmytradeagent.tdbff.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader;
import com.ohmytradeagent.tdbff.positions.PositionsReader;
import com.ohmytradeagent.tdbff.positions.PositionsReader.OpenPosition;
import com.ohmytradeagent.tdbff.proximity.MarketDataQuoteClient;
import com.ohmytradeagent.tdbff.proximity.MarketDataQuoteClient.OptionQuote;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Issue #779 T6: {@code /api/floor-breach} three-state contract. The distinction these tests exist
 * to hold: EVERY quote-unavailable shape (client returns null, client throws, null bid) maps to
 * {@code "unknown"} and NEVER to {@code "ok"} — a monitoring failure must never read as an
 * all-clear on a real-money position.
 */
@WebMvcTest(FloorBreachController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
class FloorBreachControllerWebMvcTest {

  private static final String OCC = "GOOGL 260918C00200000";

  @Autowired private MockMvc mvc;
  @MockitoBean private PositionsReader reader;
  @MockitoBean private MarketDataQuoteClient quotes;
  @MockitoBean private DbStrategyConfigReader configReader;

  private static OpenPosition position() {
    return new OpenPosition(
        "wf1", "s-copytrade-v1", OCC, 3, new BigDecimal("2.00"), new BigDecimal("600"));
  }

  @Test
  void missingTenantHeaderIs401() throws Exception {
    mvc.perform(get("/api/floor-breach")).andExpect(status().isUnauthorized());
  }

  @Test
  void bidBelowTheLine_isBreach_withFullRowShape() throws Exception {
    when(reader.openPositions("acme")).thenReturn(List.of(position()));
    when(configReader.floorBreachAlertPct("acme", "s-copytrade-v1")).thenReturn(null);
    when(quotes.optionQuote(OCC))
        .thenReturn(
            new OptionQuote(
                new BigDecimal("0.80"), new BigDecimal("1.00"), new BigDecimal("1.20")));

    mvc.perform(get("/api/floor-breach").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenant_id").value("acme"))
        .andExpect(jsonPath("$.positions[0].workflow_id").value("wf1"))
        .andExpect(jsonPath("$.positions[0].contract_symbol").value(OCC))
        .andExpect(jsonPath("$.positions[0].floor_status").value("breach"))
        .andExpect(jsonPath("$.positions[0].loss_pct").value(0.60))
        .andExpect(jsonPath("$.positions[0].entry_premium").value(2.00))
        .andExpect(jsonPath("$.positions[0].current_bid").value(0.80))
        .andExpect(jsonPath("$.positions[0].floor_line").value(1.00));
  }

  @Test
  void bidAboveTheLine_isOk() throws Exception {
    when(reader.openPositions("acme")).thenReturn(List.of(position()));
    when(configReader.floorBreachAlertPct("acme", "s-copytrade-v1")).thenReturn(null);
    when(quotes.optionQuote(OCC))
        .thenReturn(
            new OptionQuote(
                new BigDecimal("1.90"), new BigDecimal("2.00"), new BigDecimal("2.10")));

    mvc.perform(get("/api/floor-breach").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.positions[0].floor_status").value("ok"));
  }

  @Test
  void quoteNull_isUnknown_neverOk() throws Exception {
    when(reader.openPositions("acme")).thenReturn(List.of(position()));
    when(configReader.floorBreachAlertPct("acme", "s-copytrade-v1")).thenReturn(null);
    when(quotes.optionQuote(OCC)).thenReturn(null);

    mvc.perform(get("/api/floor-breach").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.positions[0].floor_status").value("unknown"));
  }

  /** THE safety case: a throwing quote client must map to "unknown", never to "ok". */
  @Test
  void quoteClientThrows_isUnknown_neverOk() throws Exception {
    when(reader.openPositions("acme")).thenReturn(List.of(position()));
    when(configReader.floorBreachAlertPct("acme", "s-copytrade-v1")).thenReturn(null);
    when(quotes.optionQuote(anyString())).thenThrow(new IllegalStateException("md down"));

    mvc.perform(get("/api/floor-breach").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.positions[0].floor_status").value("unknown"));
  }

  @Test
  void nullBid_isUnknown() throws Exception {
    when(reader.openPositions("acme")).thenReturn(List.of(position()));
    when(configReader.floorBreachAlertPct("acme", "s-copytrade-v1")).thenReturn(null);
    when(quotes.optionQuote(OCC))
        .thenReturn(new OptionQuote(null, new BigDecimal("1.00"), new BigDecimal("1.20")));

    mvc.perform(get("/api/floor-breach").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.positions[0].floor_status").value("unknown"));
  }

  @Test
  void zeroBidWithLiveAsk_isBreachAtMinus100() throws Exception {
    when(reader.openPositions("acme")).thenReturn(List.of(position()));
    when(configReader.floorBreachAlertPct("acme", "s-copytrade-v1")).thenReturn(null);
    when(quotes.optionQuote(OCC))
        .thenReturn(
            new OptionQuote(BigDecimal.ZERO, new BigDecimal("0.03"), new BigDecimal("0.05")));

    mvc.perform(get("/api/floor-breach").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.positions[0].floor_status").value("breach"))
        .andExpect(jsonPath("$.positions[0].loss_pct").value(1));
  }

  @Test
  void thresholdReadFailure_defaultsTo50pct_andStillEvaluates() throws Exception {
    when(reader.openPositions("acme")).thenReturn(List.of(position()));
    when(configReader.floorBreachAlertPct(anyString(), anyString()))
        .thenThrow(new IllegalStateException("orchestrator db down"));
    // bid 0.80 < 1.00 (the DEFAULT 0.50 line on entry 2.00) → still a breach.
    when(quotes.optionQuote(OCC))
        .thenReturn(
            new OptionQuote(
                new BigDecimal("0.80"), new BigDecimal("1.00"), new BigDecimal("1.20")));

    mvc.perform(get("/api/floor-breach").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.positions[0].floor_status").value("breach"))
        .andExpect(jsonPath("$.positions[0].floor_line").value(1.00));
  }

  @Test
  void tighterConfiguredThreshold_movesTheLine() throws Exception {
    when(reader.openPositions("acme")).thenReturn(List.of(position()));
    // 0.30 → line = 2.00 x 0.70 = 1.40; bid 1.35 breaches the tighter line but not the default.
    when(configReader.floorBreachAlertPct("acme", "s-copytrade-v1"))
        .thenReturn(new BigDecimal("0.30"));
    when(quotes.optionQuote(OCC))
        .thenReturn(
            new OptionQuote(
                new BigDecimal("1.35"), new BigDecimal("1.45"), new BigDecimal("1.55")));

    mvc.perform(get("/api/floor-breach").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.positions[0].floor_status").value("breach"))
        .andExpect(jsonPath("$.positions[0].floor_line").value(1.40));
  }
}
