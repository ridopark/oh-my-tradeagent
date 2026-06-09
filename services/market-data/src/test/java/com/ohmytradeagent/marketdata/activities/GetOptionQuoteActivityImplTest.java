package com.ohmytradeagent.marketdata.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.GetOptionQuoteRequest;
import com.ohmytradeagent.contract.OptionQuoteResult;
import com.ohmytradeagent.marketdata.provider.MarketDataProvider;
import com.ohmytradeagent.marketdata.provider.Quote;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Plan-2A R-AA-2: verifies GetOptionQuoteActivityImpl maps the provider's internal Quote record
 * into the contract DTO, and that an absent/erroring snapshot is returned (not thrown) so the
 * bounded flatten path can fall back to a marketable exit.
 */
class GetOptionQuoteActivityImplTest {

  private static final String OCC = "NVDA  260516C00140000";

  private GetOptionQuoteRequest request() {
    GetOptionQuoteRequest r = new GetOptionQuoteRequest();
    r.setSchemaVersion(1L);
    r.setTenantId("dev");
    r.setStrategyId("copytrade-v1");
    r.setContractSymbol(OCC);
    return r;
  }

  @Test
  void returnsBidMidAsk_fromProviderSnapshot() {
    MarketDataProvider provider = mock(MarketDataProvider.class);
    OffsetDateTime at = OffsetDateTime.parse("2026-05-13T17:55:00Z");
    when(provider.snapshotQuote(OCC))
        .thenReturn(
            Optional.of(
                new Quote(
                    OCC,
                    new BigDecimal("2.90"),
                    new BigDecimal("2.95"),
                    new BigDecimal("3.00"),
                    at)));

    OptionQuoteResult result = new GetOptionQuoteActivityImpl(provider).getOptionQuote(request());

    assertThat(result.getStatus()).isEqualTo(OptionQuoteResult.Status.OK);
    assertThat(result.getContractSymbol()).isEqualTo(OCC);
    assertThat(result.getBid()).isEqualByComparingTo("2.90");
    assertThat(result.getMid()).isEqualByComparingTo("2.95");
    assertThat(result.getAsk()).isEqualByComparingTo("3.00");
    assertThat(result.getRetrievedAt()).isEqualTo(at);
  }

  @Test
  void returnsUnavailable_whenProviderHasNoSnapshot() {
    MarketDataProvider provider = mock(MarketDataProvider.class);
    when(provider.snapshotQuote(OCC)).thenReturn(Optional.empty());

    OptionQuoteResult result = new GetOptionQuoteActivityImpl(provider).getOptionQuote(request());

    assertThat(result.getStatus()).isEqualTo(OptionQuoteResult.Status.UNAVAILABLE);
    assertThat(result.getBid()).isNull();
    assertThat(result.getRetrievedAt()).isNotNull();
  }

  @Test
  void returnsFailed_whenProviderThrows() {
    MarketDataProvider provider = mock(MarketDataProvider.class);
    when(provider.snapshotQuote(OCC)).thenThrow(new RuntimeException("feed down"));

    OptionQuoteResult result = new GetOptionQuoteActivityImpl(provider).getOptionQuote(request());

    assertThat(result.getStatus()).isEqualTo(OptionQuoteResult.Status.FAILED);
    assertThat(result.getError()).contains("feed down");
  }
}
