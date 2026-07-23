package com.ohmytradeagent.exec.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.PortfolioHistoryRequest;
import com.ohmytradeagent.contract.PortfolioHistoryResult;
import com.ohmytradeagent.exec.broker.BrokerClientRegistry;
import com.ohmytradeagent.exec.broker.FixedBrokerClientRegistry;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Live-account-view: unit-pins the thin portfolio-history Activity wrapper. It must surface the
 * broker's {@code getPortfolioHistory(...)} parallel arrays + scalars as the result, stamp the
 * schema version, and resolve the broker by tenant (falling back to ACCOUNT_LEVEL when blank).
 */
class PortfolioHistoryExecActivityImplTest {

  private static OptionsBroker.PortfolioHistory sampleHistory() {
    return new OptionsBroker.PortfolioHistory(
        new long[] {1719446400L, 1719532800L},
        new BigDecimal[] {new BigDecimal("10000.00"), new BigDecimal("10120.50")},
        new BigDecimal[] {new BigDecimal("0.00"), new BigDecimal("120.50")},
        new BigDecimal[] {new BigDecimal("0.0"), new BigDecimal("0.01205")},
        new BigDecimal("10000.00"),
        1719360000L,
        "1D");
  }

  private static PortfolioHistoryRequest request() {
    PortfolioHistoryRequest req = new PortfolioHistoryRequest();
    req.setSchemaVersion(1L);
    req.setBrokerTarget(PortfolioHistoryRequest.BrokerTarget.ALPACA_PAPER);
    req.setPeriod("1M");
    req.setTimeframe("1D");
    return req;
  }

  @Test
  void portfolioHistory_surfacesBrokerSeriesAndSchemaVersion() {
    OptionsBroker broker = mock(OptionsBroker.class);
    when(broker.getPortfolioHistory(any(), any(), any())).thenReturn(sampleHistory());
    PortfolioHistoryExecActivityImpl impl =
        new PortfolioHistoryExecActivityImpl(new FixedBrokerClientRegistry(broker));

    PortfolioHistoryResult result = impl.portfolioHistory(request());

    assertThat(result.getSchemaVersion()).isEqualTo(1L);
    assertThat(result.getTimestamps()).containsExactly(1719446400L, 1719532800L);
    assertThat(result.getEquity())
        .containsExactly(new BigDecimal("10000.00"), new BigDecimal("10120.50"));
    assertThat(result.getProfitLoss())
        .containsExactly(new BigDecimal("0.00"), new BigDecimal("120.50"));
    assertThat(result.getProfitLossPct())
        .containsExactly(new BigDecimal("0.0"), new BigDecimal("0.01205"));
    assertThat(result.getBaseValue()).isEqualByComparingTo(new BigDecimal("10000.00"));
    assertThat(result.getBaseValueAsof()).isEqualTo(1719360000L);
    assertThat(result.getTimeframe()).isEqualTo("1D");
  }

  @Test
  void portfolioHistory_surfacesCashFlowsAndMarksAvailable() {
    // Deposit-adjustment: the impl makes a SECOND broker call (getAccountActivities) over the
    // history window and surfaces the flows as the parallel arrays + cash_flows_available=true.
    OptionsBroker broker = mock(OptionsBroker.class);
    when(broker.getPortfolioHistory(any(), any(), any())).thenReturn(sampleHistory());
    when(broker.getAccountActivities(eq(1719446400L), anyLong()))
        .thenReturn(
            java.util.List.of(
                new OptionsBroker.AccountCashFlow(1719450000L, new BigDecimal("41230.00")),
                new OptionsBroker.AccountCashFlow(1719460000L, new BigDecimal("-500.00"))));
    PortfolioHistoryExecActivityImpl impl =
        new PortfolioHistoryExecActivityImpl(new FixedBrokerClientRegistry(broker));

    PortfolioHistoryResult result = impl.portfolioHistory(request());

    assertThat(result.getCashFlowsAvailable()).isTrue();
    assertThat(result.getCashFlowTimestamps()).containsExactly(1719450000L, 1719460000L);
    assertThat(result.getCashFlowAmounts())
        .containsExactly(new BigDecimal("41230.00"), new BigDecimal("-500.00"));
    // Lower bound is the first history timestamp; the UPPER bound is extended to max(series-last,
    // NOW) so a TODAY-dated flow (dated after the last COMPLETED daily bar but inside the live EV)
    // is captured for the BFF to net out — the sample's historical ts_last (2024-06) is superseded
    // by NOW.
    ArgumentCaptor<Long> from = ArgumentCaptor.forClass(Long.class);
    ArgumentCaptor<Long> to = ArgumentCaptor.forClass(Long.class);
    verify(broker).getAccountActivities(from.capture(), to.capture());
    assertThat(from.getValue()).isEqualTo(1719446400L);
    long now = Instant.now().getEpochSecond();
    assertThat(to.getValue()).isEqualTo(Math.max(1719532800L, now));
    // NOW (>= 2026) dominates the historical series-last (2024-06), proving the extension.
    assertThat(to.getValue()).isGreaterThan(1719532800L);
    assertThat(to.getValue()).isGreaterThanOrEqualTo(now - 5);
  }

  @Test
  void portfolioHistory_degradesWhenActivitiesThrow() {
    // Graceful degrade: getAccountActivities throwing (broker error, or a StubBroker/non-Alpaca
    // UnsupportedOperationException) leaves the history intact and reports
    // cash_flows_available=false with empty flow arrays — the BFF nulls the range number.
    OptionsBroker broker = mock(OptionsBroker.class);
    when(broker.getPortfolioHistory(any(), any(), any())).thenReturn(sampleHistory());
    when(broker.getAccountActivities(anyLong(), anyLong()))
        .thenThrow(new UnsupportedOperationException("not supported"));
    PortfolioHistoryExecActivityImpl impl =
        new PortfolioHistoryExecActivityImpl(new FixedBrokerClientRegistry(broker));

    PortfolioHistoryResult result = impl.portfolioHistory(request());

    // History still surfaced.
    assertThat(result.getEquity())
        .containsExactly(new BigDecimal("10000.00"), new BigDecimal("10120.50"));
    // Degrade signalled.
    assertThat(result.getCashFlowsAvailable()).isFalse();
    assertThat(result.getCashFlowTimestamps()).isEmpty();
    assertThat(result.getCashFlowAmounts()).isEmpty();
  }

  @Test
  void portfolioHistory_emptyWindow_leavesCashFlowFieldsUnsetAndSkipsTheLookup() {
    // An empty history window has no [first..last] bounds to query, so the impl skips the second
    // broker call entirely and never sets cash_flows_available. The BFF treats a null
    // cash_flows_available exactly like false → the range line renders "—". (The parallel arrays
    // stay at the generated POJO's initialized-empty default, which is why availability — not
    // array emptiness — is the discriminator the BFF reads.)
    OptionsBroker broker = mock(OptionsBroker.class);
    when(broker.getPortfolioHistory(any(), any(), any()))
        .thenReturn(
            new OptionsBroker.PortfolioHistory(
                new long[] {},
                new BigDecimal[] {},
                new BigDecimal[] {},
                new BigDecimal[] {},
                null,
                null,
                "1D"));
    PortfolioHistoryExecActivityImpl impl =
        new PortfolioHistoryExecActivityImpl(new FixedBrokerClientRegistry(broker));

    PortfolioHistoryResult result = impl.portfolioHistory(request());

    assertThat(result.getCashFlowsAvailable()).isNull();
    verify(broker, org.mockito.Mockito.never()).getAccountActivities(anyLong(), anyLong());
  }

  @Test
  void portfolioHistory_passesResolvedPeriodAndTimeframeThrough() {
    OptionsBroker broker = mock(OptionsBroker.class);
    when(broker.getPortfolioHistory(any(), any(), any())).thenReturn(sampleHistory());
    PortfolioHistoryExecActivityImpl impl =
        new PortfolioHistoryExecActivityImpl(new FixedBrokerClientRegistry(broker));

    impl.portfolioHistory(request());

    // The activity is a dumb pass-through: period/timeframe go straight to the broker, date_end
    // null.
    verify(broker).getPortfolioHistory("1M", "1D", null);
  }

  @Test
  void portfolioHistory_resolvesByTenantWhenPresent() {
    OptionsBroker broker = mock(OptionsBroker.class);
    when(broker.getPortfolioHistory(any(), any(), any())).thenReturn(sampleHistory());
    BrokerClientRegistry registry = mock(BrokerClientRegistry.class);
    when(registry.brokerFor(eq("staging_paper"), eq("alpaca"))).thenReturn(broker);
    PortfolioHistoryExecActivityImpl impl = new PortfolioHistoryExecActivityImpl(registry);

    PortfolioHistoryRequest req = request();
    req.setTenantId("staging_paper");
    impl.portfolioHistory(req);

    verify(registry).brokerFor("staging_paper", "alpaca");
  }

  @Test
  void portfolioHistory_fallsBackToAccountLevelWhenTenantBlank() {
    OptionsBroker broker = mock(OptionsBroker.class);
    when(broker.getPortfolioHistory(any(), any(), any())).thenReturn(sampleHistory());
    BrokerClientRegistry registry = mock(BrokerClientRegistry.class);
    when(registry.brokerFor(eq(BrokerClientRegistry.ACCOUNT_LEVEL), eq("alpaca")))
        .thenReturn(broker);
    PortfolioHistoryExecActivityImpl impl = new PortfolioHistoryExecActivityImpl(registry);

    for (String blank : new String[] {null, "", "   "}) {
      PortfolioHistoryRequest req = request();
      req.setTenantId(blank);
      impl.portfolioHistory(req);
    }

    verify(registry, org.mockito.Mockito.times(3))
        .brokerFor(BrokerClientRegistry.ACCOUNT_LEVEL, "alpaca");
  }
}
