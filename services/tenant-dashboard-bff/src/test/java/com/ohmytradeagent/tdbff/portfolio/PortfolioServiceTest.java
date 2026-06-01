package com.ohmytradeagent.tdbff.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.tdbff.platform.TenantStrategyResolver;
import com.ohmytradeagent.tdbff.platform.YamlStrategyRegistry;
import com.ohmytradeagent.tdbff.positions.PositionsReader;
import com.ohmytradeagent.tdbff.positions.PositionsReader.OpenPosition;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PortfolioServiceTest {

  private final PositionsReader positionsReader = mock(PositionsReader.class);
  private final RealizedPnlCalculator realizedPnl = mock(RealizedPnlCalculator.class);
  private final AccountEquityClient accountEquity = mock(AccountEquityClient.class);
  private final TenantStrategyResolver strategyResolver = mock(TenantStrategyResolver.class);
  private final YamlStrategyRegistry strategyRegistry = mock(YamlStrategyRegistry.class);

  private final PortfolioService service =
      new PortfolioService(
          positionsReader, realizedPnl, accountEquity, strategyResolver, strategyRegistry);

  @Test
  @SuppressWarnings("unchecked")
  void sumsRealizedPnlAcrossStrategiesAndUnionsEquityPerBrokerTarget() {
    // Two strategies; realized P&L must be summed and the two equal broker_targets unioned to one.
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("s1", "s2"));
    when(positionsReader.openPositions("acme"))
        .thenReturn(
            List.of(
                new OpenPosition(
                    "wf1", "s1", "SYM1", 2, new BigDecimal("1.50"), new BigDecimal("300.00")),
                new OpenPosition(
                    "wf2", "s2", "SYM2", 1, new BigDecimal("2.00"), new BigDecimal("200.00"))));
    when(realizedPnl.computeRealizedPnl(eq("acme"), eq("s1"), any(LocalDate.class)))
        .thenReturn(new BigDecimal("100.00"));
    when(realizedPnl.computeRealizedPnl(eq("acme"), eq("s2"), any(LocalDate.class)))
        .thenReturn(new BigDecimal("50.00"));
    when(strategyRegistry.brokerTarget("acme", "s1")).thenReturn("alpaca-paper");
    when(strategyRegistry.brokerTarget("acme", "s2")).thenReturn("alpaca-paper"); // same -> union
    when(accountEquity.equityFor("alpaca-paper")).thenReturn(new BigDecimal("10000.00"));

    Map<String, Object> body = service.portfolio("acme");

    assertThat(body.get("tenant_id")).isEqualTo("acme");
    // P&L summed across strategies: 100 + 50.
    assertThat(body.get("realized_pnl_today")).isEqualTo(new BigDecimal("150.00"));
    // Open notional summed across positions: 300 + 200.
    assertThat(body.get("sum_open_notional")).isEqualTo(new BigDecimal("500.00"));
    assertThat(body.get("open_positions_count")).isEqualTo(2);
    // Both strategies route to the same broker_target -> a single equity entry.
    var equity = (List<Map<String, Object>>) body.get("account_equity");
    assertThat(equity).hasSize(1);
    assertThat(equity.get(0)).containsEntry("broker_target", "alpaca-paper");
    assertThat(equity.get(0)).containsEntry("equity", new BigDecimal("10000.00"));
    // Honest-labeling fields are always present.
    assertThat(body).containsEntry("unrealized_pnl", null);
    assertThat(body.get("account_equity_scope")).asString().contains("NOT this tenant's");
  }

  @Test
  @SuppressWarnings("unchecked")
  void distinctBrokerTargetsEachGetTheirOwnEquityEntry() {
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("s1", "s2"));
    when(positionsReader.openPositions("acme")).thenReturn(List.of());
    when(realizedPnl.computeRealizedPnl(eq("acme"), any(), any(LocalDate.class)))
        .thenReturn(BigDecimal.ZERO);
    when(strategyRegistry.brokerTarget("acme", "s1")).thenReturn("alpaca-paper");
    when(strategyRegistry.brokerTarget("acme", "s2")).thenReturn("tradier-paper");
    when(accountEquity.equityFor("alpaca-paper")).thenReturn(new BigDecimal("100"));
    when(accountEquity.equityFor("tradier-paper")).thenReturn(new BigDecimal("200"));

    Map<String, Object> body = service.portfolio("acme");

    var equity = (List<Map<String, Object>>) body.get("account_equity");
    assertThat(equity).hasSize(2);
    assertThat(equity)
        .extracting(m -> m.get("broker_target"))
        .containsExactly("alpaca-paper", "tradier-paper");
  }
}
