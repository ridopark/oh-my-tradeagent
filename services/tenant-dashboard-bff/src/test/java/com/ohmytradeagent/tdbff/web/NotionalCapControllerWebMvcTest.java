package com.ohmytradeagent.tdbff.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader;
import com.ohmytradeagent.tdbff.platform.TenantStrategyResolver;
import com.ohmytradeagent.tdbff.portfolio.AccountEquityClient;
import com.ohmytradeagent.tdbff.positions.PositionsReader;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Seam D — {@code @WebMvcTest(NotionalCapController.class)}, filters OFF. Exercises the formula,
 * every {@code reason} branch, the response shape, and the one-snapshot-per-broker-target
 * optimisation. The filter-level allowlist pin (Seam A) is in {@code ServiceTokenFilterTest}.
 */
@WebMvcTest(NotionalCapController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
class NotionalCapControllerWebMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private TenantStrategyResolver strategyResolver;
  @MockitoBean private DbStrategyConfigReader configReader;
  @MockitoBean private AccountEquityClient equityClient;
  @MockitoBean private PositionsReader positionsReader;

  // -------------------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------------------

  /** Positions: entry_premium=2.50, qty=4 → open_notional=1000; entry_premium=1.00, qty=2 → 200. */
  private static List<PositionsReader.OpenPosition> twoPositions() {
    return List.of(
        new PositionsReader.OpenPosition(
            "wf1",
            "copytrade",
            "SPY 240101C00450000",
            4L,
            new BigDecimal("2.50"),
            new BigDecimal("1000.00")),
        new PositionsReader.OpenPosition(
            "wf2",
            "copytrade",
            "SPY 240201C00450000",
            2L,
            new BigDecimal("1.00"),
            new BigDecimal("200.00")));
  }

  /**
   * BrokerAccount with only the {@code cash} component set; all other fields are null (the
   * NotionalCap controller only reads {@code cash()}).
   */
  private static AccountEquityClient.BrokerAccount acctWithCash(BigDecimal cash) {
    return new AccountEquityClient.BrokerAccount(
        null, null, null, null, null, null, null, null, cash);
  }

  // -------------------------------------------------------------------------------------
  // Test 1 — golden path: headroom is computed as the risk gate does
  // -------------------------------------------------------------------------------------

  @Test
  void headroom_isComputedAsTheRiskGateDoes() throws Exception {
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("copytrade"));
    when(configReader.brokerTarget("acme", "copytrade")).thenReturn("alpaca-live");
    when(configReader.notionalCapPctOfCapitalBase("acme", "copytrade"))
        .thenReturn(new BigDecimal("0.5"));
    when(equityClient.snapshotFor("acme", "alpaca-live"))
        .thenReturn(acctWithCash(new BigDecimal("10000")));
    when(positionsReader.openPositions("acme")).thenReturn(twoPositions());

    mvc.perform(get("/api/notional-cap").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenant_id").value("acme"))
        .andExpect(jsonPath("$.sum_open_notional").value(1200))
        .andExpect(jsonPath("$.open_positions_count").value(2))
        .andExpect(jsonPath("$.strategies[0].broker_target").value("alpaca-live"))
        .andExpect(jsonPath("$.strategies[0].notional_cap_pct_of_capital_base").value(0.5))
        .andExpect(jsonPath("$.strategies[0].cash").value(10000))
        .andExpect(jsonPath("$.strategies[0].capital_base").value(11200))
        .andExpect(jsonPath("$.strategies[0].cap").value(5600))
        .andExpect(jsonPath("$.strategies[0].headroom").value(4400))
        .andExpect(jsonPath("$.strategies[0].reason").doesNotExist());
  }

  // -------------------------------------------------------------------------------------
  // Test 2 — over cap: negative headroom reported as-is
  // -------------------------------------------------------------------------------------

  @Test
  void overCap_reportsNegativeHeadroom() throws Exception {
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("copytrade"));
    when(configReader.brokerTarget("acme", "copytrade")).thenReturn("alpaca-live");
    when(configReader.notionalCapPctOfCapitalBase("acme", "copytrade"))
        .thenReturn(new BigDecimal("0.1"));
    when(equityClient.snapshotFor("acme", "alpaca-live"))
        .thenReturn(acctWithCash(new BigDecimal("1000")));
    when(positionsReader.openPositions("acme")).thenReturn(twoPositions());

    // capital_base = 1000 + 1200 = 2200; cap = 2200 * 0.1 = 220; headroom = 220 - 1200 = -980
    mvc.perform(get("/api/notional-cap").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.strategies[0].cap").value(220))
        .andExpect(jsonPath("$.strategies[0].headroom").value(-980))
        .andExpect(jsonPath("$.strategies[0].reason").doesNotExist());
  }

  // -------------------------------------------------------------------------------------
  // Test 3 — null cash → cash_unavailable with no numbers
  // -------------------------------------------------------------------------------------

  @Test
  void nullCash_isCashUnavailable_withNoNumbers() throws Exception {
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("copytrade"));
    when(configReader.brokerTarget("acme", "copytrade")).thenReturn("alpaca-live");
    // pct must be non-null so we reach the cash-dependent path (pct null → cap_not_configured)
    when(configReader.notionalCapPctOfCapitalBase("acme", "copytrade"))
        .thenReturn(new BigDecimal("0.5"));
    when(equityClient.snapshotFor("acme", "alpaca-live"))
        .thenReturn(new AccountEquityClient.BrokerAccount(null, null));
    when(positionsReader.openPositions("acme")).thenReturn(twoPositions());

    mvc.perform(get("/api/notional-cap").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sum_open_notional").value(1200)) // positions succeeded
        .andExpect(jsonPath("$.strategies[0].cash").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(
            jsonPath("$.strategies[0].capital_base").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.strategies[0].cap").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.strategies[0].headroom").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.strategies[0].reason").value("cash_unavailable"));
  }

  // -------------------------------------------------------------------------------------
  // Test 4 — zero cash → cash_unavailable (gate's signum() <= 0)
  // -------------------------------------------------------------------------------------

  @Test
  void zeroCash_isCashUnavailable() throws Exception {
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("copytrade"));
    when(configReader.brokerTarget("acme", "copytrade")).thenReturn("alpaca-live");
    // pct must be non-null so we reach the cash-dependent path (pct null → cap_not_configured)
    when(configReader.notionalCapPctOfCapitalBase("acme", "copytrade"))
        .thenReturn(new BigDecimal("0.5"));
    when(equityClient.snapshotFor("acme", "alpaca-live")).thenReturn(acctWithCash(BigDecimal.ZERO));
    when(positionsReader.openPositions("acme")).thenReturn(twoPositions());

    mvc.perform(get("/api/notional-cap").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.strategies[0].cash").value(0))
        .andExpect(
            jsonPath("$.strategies[0].capital_base").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.strategies[0].cap").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.strategies[0].headroom").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.strategies[0].reason").value("cash_unavailable"));
  }

  // -------------------------------------------------------------------------------------
  // Test 5 — null pct → cap_not_configured; cash and capital_base still reported
  // -------------------------------------------------------------------------------------

  @Test
  void nullPct_isCapNotConfigured() throws Exception {
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("copytrade"));
    when(configReader.brokerTarget("acme", "copytrade")).thenReturn("alpaca-live");
    when(configReader.notionalCapPctOfCapitalBase("acme", "copytrade")).thenReturn(null);
    when(equityClient.snapshotFor("acme", "alpaca-live"))
        .thenReturn(acctWithCash(new BigDecimal("10000")));
    when(positionsReader.openPositions("acme")).thenReturn(twoPositions());

    // capital_base = cash + sum_open_notional even when pct absent; cap/headroom null
    mvc.perform(get("/api/notional-cap").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.strategies[0].cash").value(10000))
        .andExpect(jsonPath("$.strategies[0].capital_base").value(11200))
        .andExpect(jsonPath("$.strategies[0].cap").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.strategies[0].headroom").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.strategies[0].reason").value("cap_not_configured"));
  }

  // -------------------------------------------------------------------------------------
  // Test 6 — no broker target → broker_target_unknown; no snapshot call
  // -------------------------------------------------------------------------------------

  @Test
  void noBrokerTarget_isBrokerTargetUnknown() throws Exception {
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("copytrade"));
    when(configReader.brokerTarget("acme", "copytrade")).thenReturn(null);
    when(positionsReader.openPositions("acme")).thenReturn(twoPositions());

    mvc.perform(get("/api/notional-cap").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.strategies[0].broker_target").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.strategies[0].cash").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.strategies[0].cap").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.strategies[0].headroom").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.strategies[0].reason").value("broker_target_unknown"));

    verify(equityClient, never()).snapshotFor(any(), any());
  }

  // -------------------------------------------------------------------------------------
  // Test 7 — config read throws → config_unavailable; cash still reported
  // -------------------------------------------------------------------------------------

  @Test
  void configReadThrows_isConfigUnavailable() throws Exception {
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("copytrade"));
    when(configReader.brokerTarget("acme", "copytrade")).thenReturn("alpaca-live");
    when(configReader.notionalCapPctOfCapitalBase("acme", "copytrade"))
        .thenThrow(new RuntimeException("DB unavailable"));
    when(equityClient.snapshotFor("acme", "alpaca-live"))
        .thenReturn(acctWithCash(new BigDecimal("10000")));
    when(positionsReader.openPositions("acme")).thenReturn(twoPositions());

    mvc.perform(get("/api/notional-cap").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.strategies[0].cash").value(10000))
        .andExpect(jsonPath("$.strategies[0].cap").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.strategies[0].headroom").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.strategies[0].reason").value("config_unavailable"));
  }

  // -------------------------------------------------------------------------------------
  // Test 8 — positions read throws → positions_unavailable everywhere; cash still reported
  // -------------------------------------------------------------------------------------

  @Test
  void positionsReadThrows_isPositionsUnavailable_everywhere() throws Exception {
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("copytrade"));
    when(configReader.brokerTarget("acme", "copytrade")).thenReturn("alpaca-live");
    when(equityClient.snapshotFor("acme", "alpaca-live"))
        .thenReturn(acctWithCash(new BigDecimal("10000")));
    PositionsReader.PositionsReadTimeoutException ex =
        Mockito.mock(PositionsReader.PositionsReadTimeoutException.class);
    when(positionsReader.openPositions("acme")).thenThrow(ex);

    mvc.perform(get("/api/notional-cap").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sum_open_notional").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.open_positions_count").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.strategies[0].cash").value(10000))
        .andExpect(jsonPath("$.strategies[0].cap").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.strategies[0].headroom").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.strategies[0].reason").value("positions_unavailable"));
  }

  // -------------------------------------------------------------------------------------
  // Test 9 — two strategies, one broker target → snapshot called exactly once
  // -------------------------------------------------------------------------------------

  @Test
  void twoStrategiesOneBrokerTarget_readsOneSnapshot() throws Exception {
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("a", "b"));
    when(configReader.brokerTarget("acme", "a")).thenReturn("alpaca-live");
    when(configReader.brokerTarget("acme", "b")).thenReturn("alpaca-live");
    when(configReader.notionalCapPctOfCapitalBase("acme", "a")).thenReturn(new BigDecimal("0.5"));
    when(configReader.notionalCapPctOfCapitalBase("acme", "b")).thenReturn(new BigDecimal("0.5"));
    when(equityClient.snapshotFor("acme", "alpaca-live"))
        .thenReturn(acctWithCash(new BigDecimal("10000")));
    when(positionsReader.openPositions("acme")).thenReturn(twoPositions());

    mvc.perform(get("/api/notional-cap").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.strategies.length()").value(2))
        .andExpect(jsonPath("$.strategies[0].cash").value(10000))
        .andExpect(jsonPath("$.strategies[1].cash").value(10000));

    // De-dup: only one snapshot call despite two strategies sharing alpaca-live.
    verify(equityClient, times(1)).snapshotFor(eq("acme"), eq("alpaca-live"));
  }

  // -------------------------------------------------------------------------------------
  // Test 10 — missing tenant header → 401
  // -------------------------------------------------------------------------------------

  @Test
  void missingTenantHeader_is401() throws Exception {
    mvc.perform(get("/api/notional-cap"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("missing_tenant"));
  }

  // -------------------------------------------------------------------------------------
  // Test D-new-1 — pct null AND cash null → cap_not_configured (gate checked before cash)
  // -------------------------------------------------------------------------------------

  @Test
  void nullPctAndNullCash_isCapNotConfigured() throws Exception {
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("copytrade"));
    when(configReader.brokerTarget("acme", "copytrade")).thenReturn("alpaca-live");
    when(configReader.notionalCapPctOfCapitalBase("acme", "copytrade")).thenReturn(null);
    when(equityClient.snapshotFor("acme", "alpaca-live"))
        .thenReturn(new AccountEquityClient.BrokerAccount(null, null));
    when(positionsReader.openPositions("acme")).thenReturn(twoPositions());

    // Gate is disabled (pct null) → cap_not_configured regardless of cash state
    mvc.perform(get("/api/notional-cap").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.strategies[0].cash").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(
            jsonPath("$.strategies[0].capital_base").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.strategies[0].cap").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.strategies[0].headroom").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.strategies[0].reason").value("cap_not_configured"));
  }

  // -------------------------------------------------------------------------------------
  // Test D-new-2 — strategyIdsForTenant throws → config_unavailable (not 500)
  // -------------------------------------------------------------------------------------

  @Test
  void strategyIdsForTenantThrows_isConfigUnavailable() throws Exception {
    when(strategyResolver.strategyIdsForTenant("acme"))
        .thenThrow(new RuntimeException("DB unavailable"));
    when(positionsReader.openPositions("acme")).thenReturn(twoPositions());

    mvc.perform(get("/api/notional-cap").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.strategies[0].reason").value("config_unavailable"));
  }

  // -------------------------------------------------------------------------------------
  // Test D-new-3 — brokerTarget throws → config_unavailable for that strategy (not 500)
  // -------------------------------------------------------------------------------------

  @Test
  void brokerTargetThrows_isConfigUnavailable() throws Exception {
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("copytrade"));
    when(configReader.brokerTarget("acme", "copytrade"))
        .thenThrow(new RuntimeException("DB unavailable"));
    when(positionsReader.openPositions("acme")).thenReturn(twoPositions());

    mvc.perform(get("/api/notional-cap").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.strategies[0].reason").value("config_unavailable"));
  }

  // -------------------------------------------------------------------------------------
  // Test 11 — response key order pinned (strict JSON comparison of test-1 body)
  // -------------------------------------------------------------------------------------

  @Test
  void responseKeyOrder_isPinned() throws Exception {
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("copytrade"));
    when(configReader.brokerTarget("acme", "copytrade")).thenReturn("alpaca-live");
    when(configReader.notionalCapPctOfCapitalBase("acme", "copytrade"))
        .thenReturn(new BigDecimal("0.5"));
    when(equityClient.snapshotFor("acme", "alpaca-live"))
        .thenReturn(acctWithCash(new BigDecimal("10000")));
    when(positionsReader.openPositions("acme")).thenReturn(twoPositions());

    // Strict mode: actual JSON must have exactly these fields, no more, no less.
    // Numbers are compared numerically (1200.00 == 1200).
    String expectedJson =
        """
        {
          "tenant_id": "acme",
          "formula": "%s",
          "sum_open_notional": 1200,
          "sum_open_notional_basis": "cost_basis_at_entry",
          "open_positions_count": 2,
          "strategies": [
            {
              "strategy_id": "copytrade",
              "broker_target": "alpaca-live",
              "notional_cap_pct_of_capital_base": 0.5,
              "cash": 10000,
              "capital_base": 11200,
              "cap": 5600,
              "headroom": 4400,
              "reason": null
            }
          ]
        }
        """
            .formatted(NotionalCapController.FORMULA);

    mvc.perform(get("/api/notional-cap").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().json(expectedJson, true));
  }
}
