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

  // --- #783 /md/option/{occ}/greeks: entry-time IV + greeks for the trade-context recorder ---

  @Test
  void optionGreeks_returnsIvAndGreeks() throws Exception {
    when(provider.snapshotGreeks("NVDA260516C00140000"))
        .thenReturn(
            Optional.of(
                new com.ohmytradeagent.marketdata.provider.OptionGreeks(
                    new BigDecimal("0.5432"),
                    new BigDecimal("0.61"),
                    new BigDecimal("0.042"),
                    new BigDecimal("-0.118"),
                    new BigDecimal("0.093"))));

    mvc.perform(get("/md/option/{occ}/greeks", "NVDA260516C00140000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.occ").value("NVDA260516C00140000"))
        .andExpect(jsonPath("$.iv").value(0.5432))
        .andExpect(jsonPath("$.delta").value(0.61))
        .andExpect(jsonPath("$.gamma").value(0.042))
        .andExpect(jsonPath("$.theta").value(-0.118))
        .andExpect(jsonPath("$.vega").value(0.093));
  }

  @Test
  void optionGreeks_unavailable_nullFields() throws Exception {
    when(provider.snapshotGreeks("SPY260609P00731000")).thenReturn(Optional.empty());

    mvc.perform(get("/md/option/{occ}/greeks", "SPY260609P00731000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.occ").value("SPY260609P00731000"))
        .andExpect(jsonPath("$.iv").doesNotExist())
        .andExpect(jsonPath("$.delta").doesNotExist());
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

  // --- /live chain picker ---

  @Test
  void chainExpirations_returnsSpotAndIsoDates() throws Exception {
    when(provider.optionExpirations("NVDA"))
        .thenReturn(
            Optional.of(
                new com.ohmytradeagent.marketdata.provider.OptionExpirations(
                    new BigDecimal("182.40"),
                    java.util.List.of(java.time.LocalDate.of(2026, 10, 16)))));

    mvc.perform(get("/md/chain/{u}/expirations", "NVDA"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.underlying").value("NVDA"))
        .andExpect(jsonPath("$.spot").value(182.40))
        .andExpect(jsonPath("$.expirations[0]").value("2026-10-16"));
  }

  @Test
  void chainExpirations_unreadable_is503_notAnEmptyList() throws Exception {
    when(provider.optionExpirations("NVDA")).thenReturn(Optional.empty());

    mvc.perform(get("/md/chain/{u}/expirations", "NVDA"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("chain_unavailable"));
  }

  @Test
  void chain_bindsExpirationAndRight_andReturnsRows() throws Exception {
    when(provider.optionChain("NVDA", java.time.LocalDate.of(2026, 10, 16), 'P'))
        .thenReturn(
            Optional.of(
                java.util.List.of(
                    new com.ohmytradeagent.marketdata.provider.ChainContract(
                        "NVDA261016P00180000",
                        new BigDecimal("180"),
                        new BigDecimal("2.10"),
                        new BigDecimal("2.15"),
                        new BigDecimal("2.20"),
                        new BigDecimal("0.44"),
                        new BigDecimal("-0.41"),
                        1200L))));

    mvc.perform(get("/md/chain/{u}", "NVDA").param("expiration", "2026-10-16").param("right", "P"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.right").value("P"))
        .andExpect(jsonPath("$.contracts[0].occ").value("NVDA261016P00180000"))
        .andExpect(jsonPath("$.contracts[0].strike").value(180))
        .andExpect(jsonPath("$.contracts[0].ask").value(2.20))
        .andExpect(jsonPath("$.contracts[0].iv").value(0.44))
        .andExpect(jsonPath("$.contracts[0].delta").value(-0.41))
        .andExpect(jsonPath("$.contracts[0].volume").value(1200));
  }

  @Test
  void chain_emptyButReadable_is200WithNoContracts() throws Exception {
    when(provider.optionChain("NVDA", java.time.LocalDate.of(2026, 10, 16), 'C'))
        .thenReturn(Optional.of(java.util.List.of()));

    mvc.perform(get("/md/chain/{u}", "NVDA").param("expiration", "2026-10-16").param("right", "C"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.contracts").isEmpty());
  }

  @Test
  void chain_unreadable_is503_notAnEmptyChain() throws Exception {
    when(provider.optionChain("NVDA", java.time.LocalDate.of(2026, 10, 16), 'C'))
        .thenReturn(Optional.empty());

    mvc.perform(get("/md/chain/{u}", "NVDA").param("expiration", "2026-10-16").param("right", "C"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("chain_unavailable"));
  }

  @Test
  void chain_rightOtherThanCOrP_is400() throws Exception {
    mvc.perform(get("/md/chain/{u}", "NVDA").param("expiration", "2026-10-16").param("right", "X"))
        .andExpect(status().isBadRequest());
  }

  // --- /live chain picker: watchlist crossing bars ---

  @Test
  void equityBars1m_returnsHighLowClose() throws Exception {
    when(provider.equityBars1Min(
            "NVDA", Instant.parse("2026-10-15T13:30:00Z"), Instant.parse("2026-10-15T14:58:00Z")))
        .thenReturn(
            Optional.of(
                java.util.List.of(
                    new com.ohmytradeagent.marketdata.provider.EquityBar(
                        Instant.parse("2026-10-15T14:42:00Z"),
                        new BigDecimal("181.20"),
                        new BigDecimal("180.70"),
                        new BigDecimal("181.06")))));

    mvc.perform(
            get("/md/equity/{t}/bars1m", "NVDA")
                .param("start", "2026-10-15T13:30:00Z")
                .param("end", "2026-10-15T14:58:00Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.bars[0].t").value("2026-10-15T14:42:00Z"))
        .andExpect(jsonPath("$.bars[0].h").value(181.20))
        .andExpect(jsonPath("$.bars[0].l").value(180.70))
        .andExpect(jsonPath("$.bars[0].c").value(181.06));
  }

  @Test
  void equityBars1m_unreadable_is503_notNoBars() throws Exception {
    when(provider.equityBars1Min(
            org.mockito.ArgumentMatchers.eq("NVDA"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(Optional.empty());

    mvc.perform(
            get("/md/equity/{t}/bars1m", "NVDA")
                .param("start", "2026-10-15T13:30:00Z")
                .param("end", "2026-10-15T14:58:00Z"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("bars_unavailable"));
  }
}
