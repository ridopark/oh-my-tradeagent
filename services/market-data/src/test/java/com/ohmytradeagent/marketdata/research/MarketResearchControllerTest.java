package com.ohmytradeagent.marketdata.research;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.marketdata.marks.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone MockMvc (real {@code @RequestParam} binding and JSON serialization) with a fresh
 * controller per test, so the global rate window never leaks between tests and the clock is
 * controllable. The beans are conditional on {@code market-data.provider=alpaca}; that wiring is
 * covered by {@code SpringComponentConstructorGuardTest} plus the condition mirroring {@code
 * AlpacaMarketDataConfig}.
 */
class MarketResearchControllerTest {

  private AlpacaResearchClient client;
  private MutableClock clock;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    client = mock(AlpacaResearchClient.class);
    clock = new MutableClock(Instant.parse("2026-11-13T15:00:00Z"));
    mvc = MockMvcBuilders.standaloneSetup(new MarketResearchController(client, clock)).build();
  }

  @Test
  void snapshot_returnsClientBody_withExplicitNulls() throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ticker", "NVDA");
    body.put("price", 142.5);
    body.put("bid", null);
    when(client.snapshot("NVDA")).thenReturn(body);

    mvc.perform(get("/md/research/snapshot").param("ticker", "NVDA"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ticker").value("NVDA"))
        .andExpect(jsonPath("$.price").value(142.5))
        .andExpect(jsonPath("$.bid").value((Object) null))
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasKey("bid")));
  }

  @Test
  void snapshot_missingTicker_is400() throws Exception {
    mvc.perform(get("/md/research/snapshot")).andExpect(status().isBadRequest());
  }

  @Test
  void bars_passesParams_defaultLimit500_endOptional() throws Exception {
    when(client.bars("SPY", "5Min", "2026-11-13", null, 500))
        .thenReturn(Map.of("ticker", "SPY", "truncated", false));

    mvc.perform(
            get("/md/research/bars")
                .param("ticker", "SPY")
                .param("timeframe", "5Min")
                .param("start", "2026-11-13"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ticker").value("SPY"))
        .andExpect(jsonPath("$.truncated").value(false));
  }

  @Test
  void bars_limitClampedTo1000() throws Exception {
    when(client.bars("SPY", "1Day", "2026-01-01", "2026-11-13", 1000))
        .thenReturn(Map.of("ticker", "SPY"));

    mvc.perform(
            get("/md/research/bars")
                .param("ticker", "SPY")
                .param("timeframe", "1Day")
                .param("start", "2026-01-01")
                .param("end", "2026-11-13")
                .param("limit", "99999"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ticker").value("SPY"));
  }

  @Test
  void bars_unknownTimeframe_is400_andNeverReachesAlpaca() throws Exception {
    mvc.perform(
            get("/md/research/bars")
                .param("ticker", "SPY")
                .param("timeframe", "2Min")
                .param("start", "2026-11-13"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_request"));

    verify(client, never()).bars(any(), any(), any(), any(), any(Integer.class));
  }

  @Test
  void news_defaultsAndClampsLimit() throws Exception {
    when(client.news(null, null, 10)).thenReturn(Map.of("news", List.of()));
    when(client.news("NVDA,AMD", "2026-11-01", 50))
        .thenReturn(Map.of("news", List.of(Map.of("id", 42))));

    mvc.perform(get("/md/research/news"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.news").isEmpty());
    mvc.perform(
            get("/md/research/news")
                .param("symbols", "NVDA,AMD")
                .param("start", "2026-11-01")
                .param("limit", "500"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.news[0].id").value(42));
  }

  @Test
  void movers_defaultsAndClampsTop() throws Exception {
    when(client.movers(10)).thenReturn(Map.of("last_updated", "a"));
    when(client.movers(50)).thenReturn(Map.of("last_updated", "b"));

    mvc.perform(get("/md/research/movers"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.last_updated").value("a"));
    mvc.perform(get("/md/research/movers").param("top", "51"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.last_updated").value("b"));
  }

  @Test
  void clock_returnsClientBody() throws Exception {
    when(client.clock()).thenReturn(Map.of("is_open", true));

    mvc.perform(get("/md/research/clock"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_open").value(true));
  }

  @Test
  void corporateActions_returnsClientBody() throws Exception {
    when(client.corporateActions("NVDA", "2024-01-01", null))
        .thenReturn(Map.of("symbol", "NVDA", "corporate_actions", Map.of()));

    mvc.perform(
            get("/md/research/corporate-actions")
                .param("symbol", "NVDA")
                .param("start", "2024-01-01"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.symbol").value("NVDA"));
  }

  @Test
  void openInterest_returnsClientBody() throws Exception {
    when(client.openInterest("NVDA", "2026-11-20", "C"))
        .thenReturn(
            Map.of("contracts", Map.of("NVDA261120C00140000", Map.of("open_interest", 1234))));

    mvc.perform(
            get("/md/research/open-interest")
                .param("underlying", "NVDA")
                .param("expiration", "2026-11-20")
                .param("right", "C"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.contracts.NVDA261120C00140000.open_interest").value(1234));
  }

  @Test
  void openInterest_badRight_is400() throws Exception {
    mvc.perform(
            get("/md/research/open-interest")
                .param("underlying", "NVDA")
                .param("expiration", "2026-11-20")
                .param("right", "call"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_request"));
  }

  @Test
  void upstreamFailure_is503() throws Exception {
    when(client.clock()).thenThrow(new ResearchUpstreamException("clock"));

    mvc.perform(get("/md/research/clock"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("upstream_unavailable"));
  }

  @Test
  void rateGuard_is429AfterTheWindowBudget_acrossRoutes_andResetsWithTheWindow() throws Exception {
    when(client.clock()).thenReturn(Map.of("is_open", true));
    when(client.snapshot("NVDA")).thenReturn(Map.of("ticker", "NVDA"));

    for (int i = 0; i < MarketResearchController.MAX_REQUESTS_PER_WINDOW; i++) {
      mvc.perform(get("/md/research/clock")).andExpect(status().isOk());
    }
    clock.advance(Duration.ofSeconds(59));
    mvc.perform(get("/md/research/snapshot").param("ticker", "NVDA"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.error").value("rate_limited"));
    verify(client, never()).snapshot(any());
    verify(client, times(MarketResearchController.MAX_REQUESTS_PER_WINDOW)).clock();

    clock.advance(Duration.ofSeconds(1));
    mvc.perform(get("/md/research/snapshot").param("ticker", "NVDA")).andExpect(status().isOk());
  }
}
