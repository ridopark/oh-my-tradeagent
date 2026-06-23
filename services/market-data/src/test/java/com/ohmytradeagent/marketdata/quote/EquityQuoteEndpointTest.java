package com.ohmytradeagent.marketdata.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.marketdata.provider.MarketDataProvider;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EquityQuoteEndpointTest {

  private final MarketDataProvider provider = mock(MarketDataProvider.class);
  private final EquityQuoteEndpoint endpoint = new EquityQuoteEndpoint(provider);

  @Test
  void presentSnapshot_returnsTickerAndPrice() {
    when(provider.snapshotEquityPrice("NVDA")).thenReturn(Optional.of(new BigDecimal("142.30")));

    Map<String, Object> out = endpoint.quote("NVDA");

    assertThat(out).containsEntry("ticker", "NVDA");
    assertThat((BigDecimal) out.get("price")).isEqualByComparingTo("142.30");
  }

  @Test
  void unavailableSnapshot_returnsNullPrice() {
    when(provider.snapshotEquityPrice("SPY")).thenReturn(Optional.empty());

    Map<String, Object> out = endpoint.quote("SPY");

    assertThat(out).containsEntry("ticker", "SPY");
    assertThat(out.get("price")).isNull();
  }
}
