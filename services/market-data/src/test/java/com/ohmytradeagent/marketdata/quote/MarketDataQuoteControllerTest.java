package com.ohmytradeagent.marketdata.quote;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.marketdata.provider.MarketDataProvider;
import com.ohmytradeagent.marketdata.provider.PremiumFeedStatus;
import com.ohmytradeagent.marketdata.provider.Quote;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
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

  // --- /md/premium-subscriptions: the #717 trail-liveness wire contract ---

  private static final String OCC = "DRAM  270319C00100000";

  /**
   * Pins the WIRE CONTRACT from the PRODUCING side. Its consumer, the BFF's
   * MarketDataLivenessClient, pins the same literals from the other side — the two are a pair and
   * must be edited together. Without both halves a renamed key breaks nothing visible: the BFF's
   * parse simply finds no match and every trail badge reads "unknown" indefinitely.
   *
   * <p>Driven through MockMvc rather than by calling the method, for the same reason as the tests
   * above: this asserts the real routing and serialisation, so it also covers the PATH the BFF
   * hardcodes. A reflection check on the @GetMapping value would not.
   */
  @Test
  void premiumSubscriptions_emitsExactlyTheKeysTheBffParses() throws Exception {
    Map<String, PremiumFeedStatus> reg = new LinkedHashMap<>();
    reg.put(
        OCC,
        new PremiumFeedStatus(
            OCC,
            1,
            4210L,
            Instant.parse("2026-08-20T14:00:09.500Z"),
            Instant.parse("2026-08-20T13:52:01.100Z"),
            0));
    when(provider.premiumFeedStatus()).thenReturn(reg);

    mvc.perform(get("/md/premium-subscriptions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.now").isString())
        .andExpect(jsonPath("$.subscriptions.length()").value(1))
        .andExpect(jsonPath("$.subscriptions[0].occ").value(OCC))
        .andExpect(jsonPath("$.subscriptions[0].subscribers").value(1))
        .andExpect(jsonPath("$.subscriptions[0].poll_ok_count").value(4210))
        // ISO-8601 strings, not epoch millis: the BFF parses these with Instant.parse.
        .andExpect(jsonPath("$.subscriptions[0].last_poll_ok_at").value("2026-08-20T14:00:09.500Z"))
        .andExpect(jsonPath("$.subscriptions[0].last_emit_at").value("2026-08-20T13:52:01.100Z"))
        .andExpect(jsonPath("$.subscriptions[0].consecutive_failures").value(0));
  }

  /**
   * An empty registry is a POSITIVE statement (nothing subscribed) — the #717 signal, not an error.
   */
  @Test
  void premiumSubscriptions_emptyRegistryIsAnEmptyList_not404() throws Exception {
    when(provider.premiumFeedStatus()).thenReturn(Map.of());

    mvc.perform(get("/md/premium-subscriptions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.subscriptions.length()").value(0));
  }

  /** Subscribed but never polled: present with null stamps. Absence must mean "no subscription". */
  @Test
  void premiumSubscriptions_neverPolledContractAppearsWithNullStamps() throws Exception {
    when(provider.premiumFeedStatus())
        .thenReturn(Map.of(OCC, new PremiumFeedStatus(OCC, 1, 0L, null, null, 0)));

    mvc.perform(get("/md/premium-subscriptions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.subscriptions[0].occ").value(OCC))
        .andExpect(jsonPath("$.subscriptions[0].last_poll_ok_at").doesNotExist());
  }
}
