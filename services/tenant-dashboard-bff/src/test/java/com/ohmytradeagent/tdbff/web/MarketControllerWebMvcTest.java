package com.ohmytradeagent.tdbff.web;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.proximity.MarketDataQuoteClient;
import com.ohmytradeagent.tdbff.proximity.MarketDataQuoteClient.ChainExpirations;
import com.ohmytradeagent.tdbff.proximity.MarketDataQuoteClient.ChainRow;
import com.ohmytradeagent.tdbff.proximity.MarketDataQuoteClient.OptionQuote;
import com.ohmytradeagent.tdbff.proximity.MarketResearchClient;
import com.ohmytradeagent.tdbff.proximity.MarketResearchClient.RateLimitedException;
import com.ohmytradeagent.tdbff.proximity.MarketResearchClient.UnavailableException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer contract for the read-only {@code /api/market/*} routes.
 *
 * <p>Manual entry is explicitly OFF for this whole class (flag false, and the allowlist names a
 * tenant no request here belongs to): every passing happy path below is therefore also proof that a
 * market read does not depend on the manual-entry write gate. {@link
 * #optionReads_answerWithManualEntryDisabled_andNoTenant()} states that case on its own.
 */
@WebMvcTest(MarketController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(
    properties = {"entries.manual.write-enabled=false", "entries.manual.allowed-tenants=other"})
class MarketControllerWebMvcTest {

  private static final String OCC = "NVDA 260821C00225000";
  private static final String CANONICAL_OCC = "NVDA  260821C00225000";

  @Autowired private MockMvc mvc;
  @MockitoBean private MarketResearchClient research;
  @MockitoBean private MarketDataQuoteClient quotes;

  // ---- happy path, one per route ---------------------------------------------------------------

  @Test
  void snapshot_proxiesNormalizedTicker() throws Exception {
    when(research.get("snapshot", Map.of("ticker", "NVDA")))
        .thenReturn(Map.of("ticker", "NVDA", "price", 181.25));

    mvc.perform(get("/api/market/snapshot").param("ticker", " nvda "))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ticker").value("NVDA"))
        .andExpect(jsonPath("$.price").value(181.25));
  }

  @Test
  void bars_forwardsEveryValidatedParam() throws Exception {
    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("ticker", "NVDA");
    expected.put("timeframe", "5Min");
    expected.put("start", "2026-11-10");
    expected.put("end", "2026-11-13T21:00:00Z");
    expected.put("limit", "200");
    when(research.get("bars", expected))
        .thenReturn(Map.of("ticker", "NVDA", "bars", List.of(), "truncated", false));

    mvc.perform(
            get("/api/market/bars")
                .param("ticker", "NVDA")
                .param("timeframe", "5Min")
                .param("start", "2026-11-10")
                .param("end", "2026-11-13T21:00:00Z")
                .param("limit", "200"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.truncated").value(false));
  }

  @Test
  void bars_optionalParamsAbsent_areNotForwarded_andLimitIsClamped() throws Exception {
    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("ticker", "NVDA");
    expected.put("timeframe", "1Day");
    expected.put("start", "2026-01-02");
    expected.put("limit", "1000");
    when(research.get("bars", expected)).thenReturn(Map.of("bars", List.of()));

    mvc.perform(
            get("/api/market/bars")
                .param("ticker", "NVDA")
                .param("timeframe", "1Day")
                .param("start", "2026-01-02")
                .param("limit", "999999"))
        .andExpect(status().isOk());

    verify(research).get("bars", expected);
  }

  @Test
  void news_forwardsNormalizedSymbols_andClampsLimit() throws Exception {
    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("symbols", "NVDA,AMD");
    expected.put("start", "2026-11-01");
    expected.put("limit", "50");
    when(research.get("news", expected)).thenReturn(Map.of("news", List.of()));

    mvc.perform(
            get("/api/market/news")
                .param("symbols", "nvda, amd")
                .param("start", "2026-11-01")
                .param("limit", "500"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.news").isArray());
  }

  @Test
  void news_withNoParams_isAllowed() throws Exception {
    when(research.get("news", Map.of())).thenReturn(Map.of("news", List.of()));

    mvc.perform(get("/api/market/news")).andExpect(status().isOk());
  }

  @Test
  void movers_clampsTop() throws Exception {
    when(research.get("movers", Map.of("top", "50"))).thenReturn(Map.of("gainers", List.of()));

    mvc.perform(get("/api/market/movers").param("top", "75"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.gainers").isArray());
  }

  @Test
  void clock_proxies() throws Exception {
    when(research.get("clock", Map.of())).thenReturn(Map.of("is_open", true));

    mvc.perform(get("/api/market/clock"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_open").value(true));
  }

  @Test
  void corporateActions_proxies() throws Exception {
    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("symbol", "NVDA");
    expected.put("start", "2026-01-01");
    expected.put("end", "2026-11-01");
    when(research.get("corporate-actions", expected))
        .thenReturn(Map.of("symbol", "NVDA", "corporate_actions", Map.of()));

    mvc.perform(
            get("/api/market/corporate-actions")
                .param("symbol", "NVDA")
                .param("start", "2026-01-01")
                .param("end", "2026-11-01"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.symbol").value("NVDA"));
  }

  @Test
  void openInterest_proxies() throws Exception {
    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("underlying", "NVDA");
    expected.put("expiration", "2026-12-18");
    expected.put("right", "C");
    when(research.get("open-interest", expected))
        .thenReturn(Map.of("underlying", "NVDA", "contracts", Map.of()));

    mvc.perform(
            get("/api/market/open-interest")
                .param("underlying", "NVDA")
                .param("expiration", "2026-12-18")
                .param("right", "C"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.underlying").value("NVDA"));
  }

  @Test
  void optionQuote_returnsParsedContractAndNbbo() throws Exception {
    when(quotes.optionQuote(CANONICAL_OCC))
        .thenReturn(
            new OptionQuote(
                new BigDecimal("2.30"), new BigDecimal("2.33"), new BigDecimal("2.35")));

    mvc.perform(get("/api/market/option-quote").param("occ", OCC))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.occ").value(CANONICAL_OCC))
        .andExpect(jsonPath("$.underlying").value("NVDA"))
        .andExpect(jsonPath("$.expiry").value("2026-08-21"))
        .andExpect(jsonPath("$.strike").value(225))
        .andExpect(jsonPath("$.right").value("C"))
        .andExpect(jsonPath("$.bid").value(2.30))
        .andExpect(jsonPath("$.mid").value(2.33))
        .andExpect(jsonPath("$.ask").value(2.35))
        .andExpect(jsonPath("$.quoted_at").isString());
  }

  @Test
  void optionQuote_missingAsk_stillAnswers_withNulls() throws Exception {
    // The manual-entry confirm step refuses this (no anchor for an order). A read must not.
    when(quotes.optionQuote(CANONICAL_OCC))
        .thenReturn(new OptionQuote(new BigDecimal("0.05"), null, null));

    mvc.perform(get("/api/market/option-quote").param("occ", OCC))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.bid").value(0.05))
        .andExpect(jsonPath("$.mid").value(nullValue()))
        .andExpect(jsonPath("$.ask").value(nullValue()));
  }

  @Test
  void optionQuote_readReturnedNothing_is503QuoteUnavailable() throws Exception {
    when(quotes.optionQuote(CANONICAL_OCC)).thenReturn(null);

    mvc.perform(get("/api/market/option-quote").param("occ", OCC))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("quote_unavailable"))
        .andExpect(jsonPath("$.occ").value(CANONICAL_OCC));
  }

  @Test
  void chainExpirations_returnsSpotAndDates() throws Exception {
    when(quotes.optionExpirations("NVDA"))
        .thenReturn(
            new ChainExpirations(
                new BigDecimal("181.25"),
                List.of(LocalDate.parse("2026-11-20"), LocalDate.parse("2026-12-18"))));

    mvc.perform(get("/api/market/chain-expirations").param("underlying", "nvda"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.underlying").value("NVDA"))
        .andExpect(jsonPath("$.spot").value(181.25))
        .andExpect(jsonPath("$.expirations[0]").value("2026-11-20"))
        .andExpect(jsonPath("$.expirations[1]").value("2026-12-18"));
  }

  @Test
  void chain_returnsRowsAndSpot() throws Exception {
    when(quotes.optionChain("NVDA", LocalDate.parse("2026-12-18"), "C"))
        .thenReturn(
            List.of(
                new ChainRow(
                    "NVDA261218C00180000",
                    new BigDecimal("180"),
                    new BigDecimal("9.10"),
                    new BigDecimal("9.20"),
                    new BigDecimal("9.30"),
                    new BigDecimal("0.41"),
                    new BigDecimal("0.55"),
                    1234L)));
    when(quotes.equityPrice("NVDA")).thenReturn(new BigDecimal("181.25"));

    mvc.perform(
            get("/api/market/chain")
                .param("underlying", "NVDA")
                .param("expiration", "2026-12-18")
                .param("right", "C"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.underlying").value("NVDA"))
        .andExpect(jsonPath("$.expiration").value("2026-12-18"))
        .andExpect(jsonPath("$.right").value("C"))
        .andExpect(jsonPath("$.spot").value(181.25))
        .andExpect(jsonPath("$.contracts[0].occ").value("NVDA261218C00180000"))
        .andExpect(jsonPath("$.contracts[0].strike").value(180))
        .andExpect(jsonPath("$.contracts[0].bid").value(9.10))
        .andExpect(jsonPath("$.contracts[0].mid").value(9.20))
        .andExpect(jsonPath("$.contracts[0].ask").value(9.30))
        .andExpect(jsonPath("$.contracts[0].iv").value(0.41))
        .andExpect(jsonPath("$.contracts[0].delta").value(0.55))
        .andExpect(jsonPath("$.contracts[0].volume").value(1234));
  }

  // ---- the manual-entry gate does not apply ----------------------------------------------------

  @Test
  void optionReads_answerWithManualEntryDisabled_andNoTenant() throws Exception {
    // entries.manual.write-enabled=false and the allowlist excludes everyone here: the same reads
    // on /api/entries/* would 404 manual_entry_disabled. No X-Tenant-Id either — not tenant data.
    when(quotes.optionQuote(CANONICAL_OCC))
        .thenReturn(new OptionQuote(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));
    when(quotes.optionExpirations("NVDA")).thenReturn(new ChainExpirations(null, List.of()));
    when(quotes.optionChain(anyString(), any(), anyString())).thenReturn(List.of());

    mvc.perform(get("/api/market/option-quote").param("occ", OCC)).andExpect(status().isOk());
    mvc.perform(get("/api/market/chain-expirations").param("underlying", "NVDA"))
        .andExpect(status().isOk());
    mvc.perform(
            get("/api/market/chain")
                .param("underlying", "NVDA")
                .param("expiration", "2026-12-18")
                .param("right", "P"))
        .andExpect(status().isOk());
  }

  // ---- input rules -> 400 invalid_request ------------------------------------------------------

  @ParameterizedTest
  @ValueSource(strings = {"", "TOOLONGX", "BRK.B", "NV1", "NVDA/../x", "NVDA&feed=iex"})
  void badTicker_is400(String ticker) throws Exception {
    mvc.perform(get("/api/market/snapshot").param("ticker", ticker))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_request"))
        .andExpect(jsonPath("$.detail").value("ticker must be a 1-6 letter ticker"));
    verifyNoInteractions(research);
  }

  @Test
  void missingRequiredParams_are400() throws Exception {
    mvc.perform(get("/api/market/snapshot"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_request"));
    mvc.perform(get("/api/market/bars").param("ticker", "NVDA").param("timeframe", "1Day"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_request"))
        .andExpect(jsonPath("$.detail").value("start is required"));
    mvc.perform(get("/api/market/corporate-actions").param("symbol", "NVDA"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("start is required"));
    mvc.perform(get("/api/market/open-interest").param("underlying", "NVDA").param("right", "C"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("expiration is required"));
    mvc.perform(get("/api/market/option-quote"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_request"));
    mvc.perform(get("/api/market/chain-expirations"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_request"));
    verifyNoInteractions(research, quotes);
  }

  @ParameterizedTest
  @ValueSource(strings = {"1min", "2Min", "1D", "1Day&x=1"})
  void badTimeframe_is400(String timeframe) throws Exception {
    mvc.perform(
            get("/api/market/bars")
                .param("ticker", "NVDA")
                .param("timeframe", timeframe)
                .param("start", "2026-11-10"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_request"));
    verifyNoInteractions(research);
  }

  @ParameterizedTest
  @ValueSource(strings = {"yesterday", "2026-13-01", "11/10/2026", "2026-11-10T09:30"})
  void badDate_is400(String start) throws Exception {
    mvc.perform(
            get("/api/market/bars")
                .param("ticker", "NVDA")
                .param("timeframe", "1Day")
                .param("start", start))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_request"))
        .andExpect(jsonPath("$.detail").value("start must be YYYY-MM-DD or an ISO-8601 instant"));
    mvc.perform(get("/api/market/news").param("start", start)).andExpect(status().isBadRequest());
    verifyNoInteractions(research);
  }

  @Test
  void dateOnlyParams_rejectAnInstant() throws Exception {
    mvc.perform(
            get("/api/market/open-interest")
                .param("underlying", "NVDA")
                .param("expiration", "2026-12-18T00:00:00Z")
                .param("right", "C"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("expiration must be YYYY-MM-DD"));
    mvc.perform(
            get("/api/market/chain")
                .param("underlying", "NVDA")
                .param("expiration", "next friday")
                .param("right", "C"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("expiration must be YYYY-MM-DD"));
    mvc.perform(
            get("/api/market/corporate-actions")
                .param("symbol", "NVDA")
                .param("start", "2026-01-01")
                .param("end", "soon"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("end must be YYYY-MM-DD"));
    verifyNoInteractions(research, quotes);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-5", "ten", "1.5", "99999999999"})
  void badLimit_is400(String limit) throws Exception {
    mvc.perform(get("/api/market/movers").param("top", limit))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_request"))
        .andExpect(jsonPath("$.detail").value("top must be a positive integer"));
    mvc.perform(get("/api/market/news").param("limit", limit)).andExpect(status().isBadRequest());
    verifyNoInteractions(research);
  }

  @ParameterizedTest
  @ValueSource(strings = {"c", "X", "CALL", ""})
  void badRight_is400(String right) throws Exception {
    mvc.perform(
            get("/api/market/chain")
                .param("underlying", "NVDA")
                .param("expiration", "2026-12-18")
                .param("right", right))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_request"))
        .andExpect(jsonPath("$.detail").value("right must be C or P"));
    mvc.perform(
            get("/api/market/open-interest")
                .param("underlying", "NVDA")
                .param("expiration", "2026-12-18")
                .param("right", right))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(research, quotes);
  }

  @Test
  void newsSymbols_badOrTooMany_are400() throws Exception {
    mvc.perform(get("/api/market/news").param("symbols", "NVDA,,AMD"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_request"));
    mvc.perform(get("/api/market/news").param("symbols", "NVDA,BRK.B"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/market/news").param("symbols", "A,".repeat(20) + "A"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("symbols must list at most 20"));
    verifyNoInteractions(research);
  }

  @Test
  void badOcc_is400() throws Exception {
    mvc.perform(get("/api/market/option-quote").param("occ", "not-an-occ"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_request"))
        .andExpect(jsonPath("$.detail").isString());
    verifyNoInteractions(quotes);
  }

  // ---- upstream failures -----------------------------------------------------------------------

  @Test
  void upstream429_is429RateLimited() throws Exception {
    when(research.get(anyString(), any())).thenThrow(new RateLimitedException());

    mvc.perform(get("/api/market/snapshot").param("ticker", "NVDA"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.error").value("rate_limited"));
  }

  @Test
  void upstreamFailure_is503MarketDataUnavailable() throws Exception {
    when(research.get(anyString(), any())).thenThrow(new UnavailableException());

    mvc.perform(get("/api/market/clock"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("market_data_unavailable"));
  }

  @Test
  void chainReadsThatReturnedNothing_are503MarketDataUnavailable() throws Exception {
    when(quotes.optionExpirations("NVDA")).thenReturn(null);
    when(quotes.optionChain(anyString(), any(), anyString())).thenReturn(null);

    mvc.perform(get("/api/market/chain-expirations").param("underlying", "NVDA"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("market_data_unavailable"));
    mvc.perform(
            get("/api/market/chain")
                .param("underlying", "NVDA")
                .param("expiration", "2026-12-18")
                .param("right", "C"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("market_data_unavailable"));
  }
}
