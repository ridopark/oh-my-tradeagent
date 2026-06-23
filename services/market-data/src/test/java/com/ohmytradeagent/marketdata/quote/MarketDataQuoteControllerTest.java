package com.ohmytradeagent.marketdata.quote;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.marketdata.provider.MarketDataProvider;
import com.ohmytradeagent.marketdata.provider.Quote;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Exercises the real Spring MVC binding (the wiring that the prior actuator {@code @Selector}
 * endpoints failed at startup — see MarketDataQuoteController). A plain method-call test would not
 * have caught it; this loads the web slice and binds the {@code @PathVariable} like production.
 */
@WebMvcTest(MarketDataQuoteController.class)
class MarketDataQuoteControllerTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private MarketDataProvider provider;

  @Test
  void equity_returnsTickerAndPrice() throws Exception {
    when(provider.snapshotEquityPrice("NVDA")).thenReturn(Optional.of(new BigDecimal("142.30")));

    mvc.perform(get("/md/equity/{ticker}", "NVDA"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ticker").value("NVDA"))
        .andExpect(jsonPath("$.price").value(142.30));
  }

  @Test
  void equity_unavailable_nullPrice() throws Exception {
    when(provider.snapshotEquityPrice("SPY")).thenReturn(Optional.empty());

    mvc.perform(get("/md/equity/{ticker}", "SPY"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ticker").value("SPY"))
        .andExpect(jsonPath("$.price").doesNotExist());
  }

  @Test
  void option_returnsBidMidAsk() throws Exception {
    when(provider.snapshotQuote("NVDA260516C00140000"))
        .thenReturn(
            Optional.of(
                new Quote(
                    "NVDA260516C00140000",
                    new BigDecimal("2.90"),
                    new BigDecimal("2.95"),
                    new BigDecimal("3.00"),
                    OffsetDateTime.parse("2026-06-23T14:31:00Z"))));

    mvc.perform(get("/md/option/{occ}", "NVDA260516C00140000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.occ").value("NVDA260516C00140000"))
        .andExpect(jsonPath("$.bid").value(2.90))
        .andExpect(jsonPath("$.mid").value(2.95))
        .andExpect(jsonPath("$.ask").value(3.00));
  }
}
