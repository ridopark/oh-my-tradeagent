package com.ohmytradeagent.tdbff.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.contract.PortfolioHistoryResult;
import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader;
import com.ohmytradeagent.tdbff.platform.TenantStrategyResolver;
import com.ohmytradeagent.tdbff.portfolio.PortfolioHistoryClient;
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
 * Guards the {@code /api/portfolio-history} web contract: the response body shape + account_scope,
 * and the shared no-`dev`-fallback (missing tenant → 401).
 */
@WebMvcTest(PortfolioHistoryController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
class PortfolioHistoryControllerWebMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private PortfolioHistoryClient client;
  @MockitoBean private TenantStrategyResolver strategyResolver;
  @MockitoBean private DbStrategyConfigReader strategyRegistry;

  @Test
  void missingTenantHeaderIs401() throws Exception {
    mvc.perform(get("/api/portfolio-history?range=1D"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("missing_tenant"));
  }

  @Test
  void returnsHistoryBodyShapeWithAccountScope() throws Exception {
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("s1"));
    when(strategyRegistry.brokerTarget("acme", "s1")).thenReturn("alpaca-paper");

    PortfolioHistoryResult result = new PortfolioHistoryResult();
    result.setSchemaVersion(1L);
    result.setTimestamps(List.of(1719446400L, 1719532800L));
    result.setEquity(List.of(new BigDecimal("10000.00"), new BigDecimal("10120.50")));
    result.setProfitLoss(List.of(new BigDecimal("0.00"), new BigDecimal("120.50")));
    result.setProfitLossPct(List.of(new BigDecimal("0.0"), new BigDecimal("0.01205")));
    result.setBaseValue(new BigDecimal("10000.00"));
    result.setBaseValueAsof(1719360000L);
    result.setTimeframe("5Min");
    when(client.historyFor(eq("acme"), eq("alpaca-paper"), eq("1D"))).thenReturn(result);

    mvc.perform(get("/api/portfolio-history?range=1D").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.timestamps.length()").value(2))
        .andExpect(jsonPath("$.equity.length()").value(2))
        .andExpect(jsonPath("$.profit_loss.length()").value(2))
        .andExpect(jsonPath("$.profit_loss_pct.length()").value(2))
        .andExpect(jsonPath("$.base_value").value(10000.00))
        .andExpect(jsonPath("$.base_value_asof").value(1719360000L))
        .andExpect(jsonPath("$.timeframe").value("5Min"))
        .andExpect(jsonPath("$.account_scope").isNotEmpty());
  }

  @Test
  void noBrokerTargetForTenant_degradesToEmptyBodyAt200() throws Exception {
    // A tenant with no strategy carrying a broker_target degrades to empty arrays at HTTP 200
    // (read-only view never 500s), and the client is never called.
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of());

    mvc.perform(get("/api/portfolio-history?range=1M").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.timestamps.length()").value(0))
        .andExpect(jsonPath("$.equity.length()").value(0))
        .andExpect(jsonPath("$.base_value").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.account_scope").isNotEmpty());
  }
}
