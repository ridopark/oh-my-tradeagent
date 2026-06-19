package com.ohmytradeagent.tdbff.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader;
import com.ohmytradeagent.tdbff.platform.TenantStrategyResolver;
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
  private final DbStrategyConfigReader strategyRegistry = mock(DbStrategyConfigReader.class);

  private final PortfolioService service = newService(false, 9);

  private PortfolioService newService(boolean exposeAccountNumber, long subreadTimeoutSeconds) {
    return new PortfolioService(
        positionsReader,
        realizedPnl,
        accountEquity,
        strategyResolver,
        strategyRegistry,
        exposeAccountNumber,
        subreadTimeoutSeconds);
  }

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
    when(accountEquity.snapshotFor("alpaca-paper"))
        .thenReturn(
            new AccountEquityClient.BrokerAccount(new BigDecimal("10000.00"), "PA3ER05HLHMB"));

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
    // Flag defaults false -> the informational account_number is NOT exposed even when present.
    assertThat(equity.get(0)).doesNotContainKey("account_number");
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
    when(accountEquity.snapshotFor("alpaca-paper"))
        .thenReturn(new AccountEquityClient.BrokerAccount(new BigDecimal("100"), null));
    when(accountEquity.snapshotFor("tradier-paper"))
        .thenReturn(new AccountEquityClient.BrokerAccount(new BigDecimal("200"), null));

    Map<String, Object> body = service.portfolio("acme");

    var equity = (List<Map<String, Object>>) body.get("account_equity");
    assertThat(equity).hasSize(2);
    assertThat(equity)
        .extracting(m -> m.get("broker_target"))
        .containsExactly("alpaca-paper", "tradier-paper");
  }

  @Test
  @SuppressWarnings("unchecked")
  void exposesAccountNumberOnlyWhenTheDevFlagIsEnabled() {
    // Same fixture, two flag states: FALSE omits account_number; TRUE includes it.
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("s1"));
    when(positionsReader.openPositions("acme")).thenReturn(List.of());
    when(realizedPnl.computeRealizedPnl(eq("acme"), any(), any(LocalDate.class)))
        .thenReturn(BigDecimal.ZERO);
    when(strategyRegistry.brokerTarget("acme", "s1")).thenReturn("alpaca-paper");
    when(accountEquity.snapshotFor("alpaca-paper"))
        .thenReturn(
            new AccountEquityClient.BrokerAccount(new BigDecimal("10000.00"), "PA3ER05HLHMB"));

    PortfolioService flagOff = newService(false, 9);
    var offRows = (List<Map<String, Object>>) flagOff.portfolio("acme").get("account_equity");
    assertThat(offRows).hasSize(1);
    assertThat(offRows.get(0)).containsEntry("equity", new BigDecimal("10000.00"));
    assertThat(offRows.get(0)).doesNotContainKey("account_number");

    PortfolioService flagOn = newService(true, 9);
    var onRows = (List<Map<String, Object>>) flagOn.portfolio("acme").get("account_equity");
    assertThat(onRows).hasSize(1);
    assertThat(onRows.get(0)).containsEntry("account_number", "PA3ER05HLHMB");
  }

  @Test
  @SuppressWarnings("unchecked")
  void positionSubReadTimeoutDegradesToEmptyNotWholePageFailure() throws Exception {
    // A stalled position-state fan-out (e.g. orchestrator worker down) must NOT fail the page: the
    // positions section degrades to empty while the (fast) equity section still comes through.
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("s1"));
    when(positionsReader.openPositions("acme"))
        .thenAnswer(
            inv -> {
              Thread.sleep(3000);
              return List.of();
            });
    when(realizedPnl.computeRealizedPnl(eq("acme"), any(), any(LocalDate.class)))
        .thenReturn(BigDecimal.ZERO);
    when(strategyRegistry.brokerTarget("acme", "s1")).thenReturn("alpaca-paper");
    when(accountEquity.snapshotFor("alpaca-paper"))
        .thenReturn(new AccountEquityClient.BrokerAccount(new BigDecimal("100"), null));

    // 1s budget so the stalled sub-read degrades quickly under test.
    PortfolioService fast = newService(false, 1);
    Map<String, Object> body = fast.portfolio("acme");

    assertThat(body.get("open_positions_count")).isEqualTo(0);
    assertThat((List<Map<String, Object>>) body.get("open_positions")).isEmpty();
    assertThat(body.get("sum_open_notional")).isEqualTo(BigDecimal.ZERO);
    // Equity sub-read was fast -> unaffected.
    var equity = (List<Map<String, Object>>) body.get("account_equity");
    assertThat(equity).hasSize(1);
    assertThat(equity.get(0)).containsEntry("equity", new BigDecimal("100"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void equitySnapshotTimeoutDegradesToNullEquityNotWholePageFailure() throws Exception {
    // A stalled AccountSnapshotWorkflow must degrade to null equity for that broker only — the row
    // is still present and the positions section is unaffected.
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("s1"));
    when(positionsReader.openPositions("acme"))
        .thenReturn(
            List.of(
                new OpenPosition(
                    "wf1", "s1", "SYM1", 1, new BigDecimal("2.00"), new BigDecimal("200.00"))));
    when(realizedPnl.computeRealizedPnl(eq("acme"), any(), any(LocalDate.class)))
        .thenReturn(BigDecimal.ZERO);
    when(strategyRegistry.brokerTarget("acme", "s1")).thenReturn("alpaca-paper");
    when(accountEquity.snapshotFor("alpaca-paper"))
        .thenAnswer(
            inv -> {
              Thread.sleep(3000);
              return new AccountEquityClient.BrokerAccount(new BigDecimal("999"), null);
            });

    PortfolioService fast = newService(false, 1);
    Map<String, Object> body = fast.portfolio("acme");

    var equity = (List<Map<String, Object>>) body.get("account_equity");
    assertThat(equity).hasSize(1);
    assertThat(equity.get(0)).containsEntry("broker_target", "alpaca-paper");
    assertThat(equity.get(0)).containsEntry("equity", null);
    // Positions sub-read was fast -> unaffected.
    assertThat(body.get("open_positions_count")).isEqualTo(1);
  }
}
