package com.ohmytradeagent.exec.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import org.junit.jupiter.api.Test;

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
