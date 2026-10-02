package com.ohmytradeagent.tdbff.web;

import static org.hamcrest.Matchers.hasEntry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.live.MarketDataMarksClient;
import com.ohmytradeagent.tdbff.live.MarketDataMarksClient.MarksResponse;
import com.ohmytradeagent.tdbff.live.OpenOccCache;
import com.ohmytradeagent.tdbff.portfolio.AccountEquityClient;
import com.ohmytradeagent.tdbff.portfolio.BrokerPositionsClient;
import com.ohmytradeagent.tdbff.positions.PositionsReader;
import com.ohmytradeagent.tdbff.positions.PositionsReader.OpenPosition;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code /api/live/marks}: the 1s holdings poll. Runs the REAL {@link OpenOccCache} so the whole
 * request path (tenant → cached OCC set → market-data) is exercised, with every broker-reaching
 * client mocked so any call to one is caught.
 */
@WebMvcTest(LiveMarksController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({TenantContext.class, OpenOccCache.class})
class LiveMarksControllerWebMvcTest {

  private static final String PADDED = "SPY   260519C00737000";
  private static final String COMPACT = "SPY260519C00737000";

  // The OpenOccCache is a real singleton shared across this class's tests (cached context), so
  // every test uses its OWN tenant id to start from a cold cache.
  @Autowired private MockMvc mvc;
  @MockitoBean private PositionsReader reader;
  @MockitoBean private MarketDataMarksClient marksClient;
  // Broker-reaching clients: present in the context ONLY so the test can prove they are never used.
  @MockitoBean private BrokerPositionsClient brokerPositions;
  @MockitoBean private AccountEquityClient accountEquity;

  private static OpenPosition pos() {
    return new OpenPosition("wf1", "s1", PADDED, 2, new BigDecimal("1.00"), new BigDecimal("200"));
  }

  private static Map<String, Object> mark(String occ) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("occ", occ);
    m.put("bid", 1.25);
    m.put("mid", 1.3);
    m.put("ask", 1.35);
    m.put("quote_at", "2026-10-01T14:00:00Z");
    m.put("polled_at", "2026-10-01T14:00:01Z");
    m.put("underlying", Map.of("ticker", "SPY", "price", 737.5, "at", "2026-10-01T14:00:00Z"));
    m.put("warming", false);
    m.put("capped", false);
    return m;
  }

  @Test
  void missingTenantHeaderIs401() throws Exception {
    mvc.perform(get("/api/live/marks")).andExpect(status().isUnauthorized());
  }

  /** Success criterion 3: the 1s marks path never reaches the broker trading API. */
  @Test
  void liveMarks_performsNoBrokerCall_criterion3() throws Exception {
    when(reader.openPositions("crit3")).thenReturn(List.of(pos()));
    when(marksClient.marks(List.of(COMPACT)))
        .thenReturn(new MarksResponse("2026-10-01T14:00:01Z", List.of(mark(COMPACT))));

    for (int i = 0; i < 3; i++) {
      mvc.perform(get("/api/live/marks").header("X-Tenant-Id", "crit3")).andExpect(status().isOk());
    }

    verifyNoInteractions(brokerPositions, accountEquity);
  }

  @Test
  void marksAreReturnedUnderTheRowsOwnContractSymbol() throws Exception {
    when(reader.openPositions("rowform")).thenReturn(List.of(pos()));
    when(marksClient.marks(List.of(COMPACT)))
        .thenReturn(new MarksResponse("2026-10-01T14:00:01Z", List.of(mark(COMPACT))));

    mvc.perform(get("/api/live/marks").header("X-Tenant-Id", "rowform"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.now").value("2026-10-01T14:00:01Z"))
        .andExpect(jsonPath("$.market_data_reachable").value(true))
        .andExpect(jsonPath("$.occs_status").value("ok"))
        .andExpect(jsonPath("$.marks.length()").value(1))
        .andExpect(jsonPath("$.marks[0].occ").value(PADDED))
        .andExpect(jsonPath("$.marks[0].bid").value(1.25))
        .andExpect(jsonPath("$.marks[0].quote_at").value("2026-10-01T14:00:00Z"))
        .andExpect(jsonPath("$.marks[0].polled_at").value("2026-10-01T14:00:01Z"))
        .andExpect(jsonPath("$.marks[0].underlying.price").value(737.5))
        .andExpect(jsonPath("$.marks[0].warming").value(false))
        .andExpect(jsonPath("$.marks[0].capped").value(false));
  }

  /**
   * A null {@code polled_at} (never polled) must reach the dashboard as a present null, not be
   * dropped: an absent key makes the dashboard fall back to {@code quote_at}.
   */
  @Test
  void aNullPolledAt_isPassedThroughAsAPresentNull() throws Exception {
    when(reader.openPositions("nullpolled")).thenReturn(List.of(pos()));
    Map<String, Object> m = mark(COMPACT);
    m.put("polled_at", null);
    when(marksClient.marks(List.of(COMPACT)))
        .thenReturn(new MarksResponse("2026-10-01T14:00:01Z", List.of(m)));

    mvc.perform(get("/api/live/marks").header("X-Tenant-Id", "nullpolled"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.marks[0]", hasEntry("polled_at", null)));
  }

  @Test
  void aMarkForAContractTheTenantDoesNotHold_isDropped() throws Exception {
    when(reader.openPositions("dropped")).thenReturn(List.of(pos()));
    when(marksClient.marks(List.of(COMPACT)))
        .thenReturn(
            new MarksResponse(
                "2026-10-01T14:00:01Z", List.of(mark(COMPACT), mark("NVDA261120C00140000"))));

    mvc.perform(get("/api/live/marks").header("X-Tenant-Id", "dropped"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.marks.length()").value(1))
        .andExpect(jsonPath("$.marks[0].occ").value(PADDED));
  }

  @Test
  void marketDataDown_is200_reachableFalse_emptyMarks() throws Exception {
    when(reader.openPositions("mddown")).thenReturn(List.of(pos()));
    when(marksClient.marks(any())).thenReturn(null);

    mvc.perform(get("/api/live/marks").header("X-Tenant-Id", "mddown"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.market_data_reachable").value(false))
        .andExpect(jsonPath("$.occs_status").value("ok"))
        .andExpect(jsonPath("$.marks.length()").value(0))
        .andExpect(jsonPath("$.now").isString());
  }

  @Test
  void positionsReadFailure_isOccsStatusUnknown_andMarketDataIsNotCalled() throws Exception {
    when(reader.openPositions("posfail")).thenThrow(new RuntimeException("temporal down"));

    mvc.perform(get("/api/live/marks").header("X-Tenant-Id", "posfail"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.occs_status").value("unknown"))
        .andExpect(jsonPath("$.market_data_reachable").value(false))
        .andExpect(jsonPath("$.marks.length()").value(0));
    verify(marksClient, never()).marks(any());
    verifyNoInteractions(brokerPositions, accountEquity);
  }

  @Test
  void noOpenPositions_isOk_withNoMarks_andNoMarketDataCall() throws Exception {
    when(reader.openPositions("nopos")).thenReturn(List.of());

    mvc.perform(get("/api/live/marks").header("X-Tenant-Id", "nopos"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.occs_status").value("ok"))
        .andExpect(jsonPath("$.marks.length()").value(0));
    verify(marksClient, never()).marks(any());
  }
}
