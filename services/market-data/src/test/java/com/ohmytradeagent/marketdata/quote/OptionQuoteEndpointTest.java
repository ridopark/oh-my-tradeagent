package com.ohmytradeagent.marketdata.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.marketdata.provider.MarketDataProvider;
import com.ohmytradeagent.marketdata.provider.Quote;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OptionQuoteEndpointTest {

  private final MarketDataProvider provider = mock(MarketDataProvider.class);
  private final OptionQuoteEndpoint endpoint = new OptionQuoteEndpoint(provider);

  @Test
  void presentSnapshot_returnsBidMidAsk() {
    when(provider.snapshotQuote("NVDA260516C00140000"))
        .thenReturn(
            Optional.of(
                new Quote(
                    "NVDA260516C00140000",
                    new BigDecimal("2.90"),
                    new BigDecimal("2.95"),
                    new BigDecimal("3.00"),
                    OffsetDateTime.parse("2026-06-23T14:31:00Z"))));

    Map<String, Object> out = endpoint.quote("NVDA260516C00140000");

    assertThat(out).containsEntry("occ", "NVDA260516C00140000");
    assertThat((BigDecimal) out.get("bid")).isEqualByComparingTo("2.90");
    assertThat((BigDecimal) out.get("mid")).isEqualByComparingTo("2.95");
    assertThat((BigDecimal) out.get("ask")).isEqualByComparingTo("3.00");
  }

  @Test
  void unavailableSnapshot_returnsNullFields() {
    when(provider.snapshotQuote("SPY260609P00731000")).thenReturn(Optional.empty());

    Map<String, Object> out = endpoint.quote("SPY260609P00731000");

    assertThat(out).containsEntry("occ", "SPY260609P00731000");
    assertThat(out.get("bid")).isNull();
    assertThat(out.get("mid")).isNull();
    assertThat(out.get("ask")).isNull();
  }
}
