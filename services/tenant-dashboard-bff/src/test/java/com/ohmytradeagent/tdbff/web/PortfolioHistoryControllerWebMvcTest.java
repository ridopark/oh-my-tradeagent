package com.ohmytradeagent.tdbff.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.contract.PortfolioHistoryResult;
import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader;
import com.ohmytradeagent.tdbff.platform.TenantStrategyResolver;
import com.ohmytradeagent.tdbff.portfolio.AccountEquityClient;
import com.ohmytradeagent.tdbff.portfolio.AccountEquityClient.BrokerAccount;
import com.ohmytradeagent.tdbff.portfolio.PortfolioHistoryClient;
import com.ohmytradeagent.tdbff.portfolio.PortfolioReturnCalculator;
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
@Import({TenantContext.class, PortfolioReturnCalculator.class})
class PortfolioHistoryControllerWebMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private PortfolioHistoryClient client;
  @MockitoBean private TenantStrategyResolver strategyResolver;
  @MockitoBean private DbStrategyConfigReader strategyRegistry;
  @MockitoBean private AccountEquityClient accountEquityClient;

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
        // No cash flows on this result → range figures null, cash_flows_available false.
        .andExpect(jsonPath("$.range_pl").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.range_pl_pct").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.cash_flows_available").value(false))
        .andExpect(jsonPath("$.account_scope").isNotEmpty());
  }

  @Test
  void depositAdjustedRangeFieldsAreEmitted() throws Exception {
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("s1"));
    when(strategyRegistry.brokerTarget("acme", "s1")).thenReturn("alpaca-live");

    // Incident frame: funded from $5k, +$41,230 deposit mid-range, equity ends 52259.56.
    PortfolioHistoryResult result = new PortfolioHistoryResult();
    result.setSchemaVersion(1L);
    result.setTimestamps(List.of(1000L, 3000L));
    result.setEquity(List.of(new BigDecimal("5000.00"), new BigDecimal("52259.56")));
    result.setBaseValue(new BigDecimal("5000.00"));
    result.setCashFlowTimestamps(List.of(2000L));
    result.setCashFlowAmounts(List.of(new BigDecimal("41230")));
    result.setCashFlowsAvailable(true);
    when(client.historyFor(eq("acme"), eq("alpaca-live"), eq("1M"))).thenReturn(result);
    // Degraded live-equity snapshot → EV falls back to equity[last]=52259.56 (behavior-preserving).
    when(accountEquityClient.snapshotFor(eq("acme"), eq("alpaca-live")))
        .thenReturn(new BrokerAccount(null, null));

    mvc.perform(get("/api/portfolio-history?range=1M").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        // Deposit-free trading P&L (52259.56 - 5000 - 41230), NOT the deposit-inflated figure.
        .andExpect(jsonPath("$.range_pl").value(6029.56))
        .andExpect(jsonPath("$.range_pl_pct").value(org.hamcrest.Matchers.notNullValue()))
        .andExpect(jsonPath("$.cash_flows_available").value(true));
  }

  @Test
  void liveEquityUsedAsEv_overStaleDailyBar() throws Exception {
    // prod-kipark frame: a daily-bar 1M range whose last point (54360.02) is yesterday's close.
    // The controller reads live equity (52577.52) and threads it as EV → range = 52577.52 - 50000.
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("s1"));
    when(strategyRegistry.brokerTarget("acme", "s1")).thenReturn("alpaca-live");

    PortfolioHistoryResult result = new PortfolioHistoryResult();
    result.setSchemaVersion(1L);
    result.setTimestamps(List.of(1000L, 3000L));
    result.setEquity(List.of(new BigDecimal("50000.00"), new BigDecimal("54360.02")));
    result.setBaseValue(new BigDecimal("50000.00"));
    result.setCashFlowTimestamps(List.of());
    result.setCashFlowAmounts(List.of());
    result.setCashFlowsAvailable(true);
    when(client.historyFor(eq("acme"), eq("alpaca-live"), eq("1M"))).thenReturn(result);
    when(accountEquityClient.snapshotFor(eq("acme"), eq("alpaca-live")))
        .thenReturn(new BrokerAccount(new BigDecimal("52577.52"), "310056593"));

    mvc.perform(get("/api/portfolio-history?range=1M").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        // Live EV, NOT the stale daily bar's 4360.02.
        .andExpect(jsonPath("$.range_pl").value(2577.52));
  }

  @Test
  void throwingEquitySnapshot_fallsBackToEquityLast_no500() throws Exception {
    // A throwing/degraded snapshot read must NEVER fail the chart: EV falls back to equity[last].
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("s1"));
    when(strategyRegistry.brokerTarget("acme", "s1")).thenReturn("alpaca-live");

    PortfolioHistoryResult result = new PortfolioHistoryResult();
    result.setSchemaVersion(1L);
    result.setTimestamps(List.of(1000L, 3000L));
    result.setEquity(List.of(new BigDecimal("50000.00"), new BigDecimal("54360.02")));
    result.setBaseValue(new BigDecimal("50000.00"));
    result.setCashFlowTimestamps(List.of());
    result.setCashFlowAmounts(List.of());
    result.setCashFlowsAvailable(true);
    when(client.historyFor(eq("acme"), eq("alpaca-live"), eq("1M"))).thenReturn(result);
    when(accountEquityClient.snapshotFor(eq("acme"), eq("alpaca-live")))
        .thenThrow(new RuntimeException("temporal unavailable"));

    mvc.perform(get("/api/portfolio-history?range=1M").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        // Fell back to the series' last point (54360.02 - 50000), no 500.
        .andExpect(jsonPath("$.range_pl").value(4360.02));
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
        .andExpect(jsonPath("$.range_pl").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.range_pl_pct").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.cash_flows_available").value(false))
        .andExpect(jsonPath("$.account_scope").isNotEmpty());
  }
}
