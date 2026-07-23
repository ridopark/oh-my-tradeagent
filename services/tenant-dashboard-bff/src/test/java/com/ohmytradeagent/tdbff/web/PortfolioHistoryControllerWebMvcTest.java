package com.ohmytradeagent.tdbff.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import java.time.Instant;
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
    when(client.usesDailyBars("1M")).thenReturn(true); // 1M is a daily-bar range → live EV read
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
    when(client.usesDailyBars("1M")).thenReturn(true); // 1M is a daily-bar range → live EV read
    when(accountEquityClient.snapshotFor(eq("acme"), eq("alpaca-live")))
        .thenReturn(new BrokerAccount(new BigDecimal("52577.52"), "310056593"));

    mvc.perform(get("/api/portfolio-history?range=1M").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        // Live EV, NOT the stale daily bar's 4360.02.
        .andExpect(jsonPath("$.range_pl").value(2577.52));
  }

  @Test
  void liveEquityEv_todayDatedCashFlow_isNettedOutNotCountedAsProfit() throws Exception {
    // SAME-DAY DEPOSIT (#615 correctness gap): the daily-bar series ends YESTERDAY (54360.02); a
    // $10k deposit lands TODAY (timestamp AFTER the series' last point) and is baked into live
    // equity (64360.02). Because the controller passes a NOW evAsOf, the flow window extends to NOW
    // so the deposit is NETTED OUT (64360.02 - 50000 - 10000 = 4360.02) rather than counted as
    // profit (14360.02). Proves the controller couples the flow window to the live-EV as-of time.
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("s1"));
    when(strategyRegistry.brokerTarget("acme", "s1")).thenReturn("alpaca-live");

    long todayFlowTs =
        Instant.now().getEpochSecond() - 3600; // after the series, before evAsOf(now)
    PortfolioHistoryResult result = new PortfolioHistoryResult();
    result.setSchemaVersion(1L);
    result.setTimestamps(List.of(1000L, 3000L));
    result.setEquity(List.of(new BigDecimal("50000.00"), new BigDecimal("54360.02")));
    result.setBaseValue(new BigDecimal("50000.00"));
    result.setCashFlowTimestamps(List.of(todayFlowTs));
    result.setCashFlowAmounts(List.of(new BigDecimal("10000")));
    result.setCashFlowsAvailable(true);
    when(client.historyFor(eq("acme"), eq("alpaca-live"), eq("1M"))).thenReturn(result);
    when(client.usesDailyBars("1M")).thenReturn(true); // 1M is a daily-bar range → live EV read
    when(accountEquityClient.snapshotFor(eq("acme"), eq("alpaca-live")))
        .thenReturn(new BrokerAccount(new BigDecimal("64360.02"), "310056593"));

    mvc.perform(get("/api/portfolio-history?range=1M").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        // Today's deposit netted (64360.02 - 50000 - 10000), NOT counted as profit (14360.02).
        .andExpect(jsonPath("$.range_pl").value(4360.02));
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
    when(client.usesDailyBars("1M")).thenReturn(true); // 1M is a daily-bar range → live EV read
    when(accountEquityClient.snapshotFor(eq("acme"), eq("alpaca-live")))
        .thenThrow(new RuntimeException("temporal unavailable"));

    mvc.perform(get("/api/portfolio-history?range=1M").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        // Fell back to the series' last point (54360.02 - 50000), no 500.
        .andExpect(jsonPath("$.range_pl").value(4360.02));
  }

  @Test
  void intradayRange_1D_skipsLiveEquityFetch() throws Exception {
    // An intraday range (1D/1W) already carries a live last series point, so the controller must
    // NOT
    // make the extra live-equity broker read — critically the 1D tab polls ~every 15s. EV falls to
    // equity[last] (already live). Guards the review fix scoping the read to daily-bar ranges only.
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("s1"));
    when(strategyRegistry.brokerTarget("acme", "s1")).thenReturn("alpaca-live");
    when(client.usesDailyBars("1D")).thenReturn(false);

    PortfolioHistoryResult result = new PortfolioHistoryResult();
    result.setSchemaVersion(1L);
    result.setTimestamps(List.of(1000L, 3000L));
    result.setEquity(List.of(new BigDecimal("50000.00"), new BigDecimal("50477.28")));
    result.setBaseValue(new BigDecimal("50000.00"));
    result.setCashFlowTimestamps(List.of());
    result.setCashFlowAmounts(List.of());
    result.setCashFlowsAvailable(true);
    when(client.historyFor(eq("acme"), eq("alpaca-live"), eq("1D"))).thenReturn(result);

    mvc.perform(get("/api/portfolio-history?range=1D").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        // EV = the series' live last point (50477.28 - 50000), computed WITHOUT an equity read.
        .andExpect(jsonPath("$.range_pl").value(477.28));

    verify(accountEquityClient, never()).snapshotFor(anyString(), anyString());
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
