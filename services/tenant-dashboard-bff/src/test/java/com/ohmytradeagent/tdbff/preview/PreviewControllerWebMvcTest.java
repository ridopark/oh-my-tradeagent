package com.ohmytradeagent.tdbff.preview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.contract.KillSwitchState;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.tdbff.live.MarketDataMarksClient;
import com.ohmytradeagent.tdbff.live.MarketDataMarksClient.MarksResponse;
import com.ohmytradeagent.tdbff.orders.OrdersReader;
import com.ohmytradeagent.tdbff.portfolio.PortfolioCache;
import com.ohmytradeagent.tdbff.positions.PositionsReader;
import com.ohmytradeagent.tdbff.positions.PositionsReader.OpenPosition;
import com.ohmytradeagent.tdbff.trades.TradesReader;
import com.ohmytradeagent.tdbff.web.TenantContext;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code /api/preview/*} end to end: the REAL {@link PreviewService} (quarantine + projection)
 * behind the controller, every reader mocked. The service caches open positions per tenant, so each
 * test uses its own tenant id.
 */
@WebMvcTest(PreviewController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({TenantContext.class, PreviewService.class})
class PreviewControllerWebMvcTest {

  private static final String SECRET = "SECRET";
  private static final Instant NOW = Instant.parse("2026-11-27T15:00:00Z");
  private static final String NVDA = "NVDA  261224C00200000";
  private static final String NVDA_COMPACT = "NVDA261224C00200000";
  private static final String GOOGL = "GOOGL 261204C00285000";
  private static final String GOOGL_COMPACT = "GOOGL261204C00285000";

  @Autowired private MockMvc mvc;
  @Autowired private PreviewService service;
  @MockitoBean private PositionsReader positions;
  @MockitoBean private PreviewEntryTimes entryTimes;
  @MockitoBean private OrdersReader ordersReader;
  @MockitoBean private TradesReader tradesReader;
  @MockitoBean private PortfolioCache portfolioCache;
  @MockitoBean private MarketDataMarksClient marksClient;
  @MockitoBean private WorkflowClient workflowClient;

  @BeforeEach
  void fixClock() {
    service.clock = Clock.fixed(NOW, ZoneOffset.UTC);
  }

  private static OffsetDateTime minutesAgo(long m) {
    return OffsetDateTime.ofInstant(NOW.minus(Duration.ofMinutes(m)), ZoneOffset.UTC);
  }

  private static OpenPosition open(String tenant, String strategy, String contract, String sig) {
    return new OpenPosition(
        WorkflowIds.position(tenant, strategy, contract, sig),
        strategy,
        contract,
        11,
        new BigDecimal("3.36"),
        new BigDecimal("3696.00"));
  }

  private static Map<String, Object> order(String contract, String side, OffsetDateTime at) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("intent_key", SECRET + ":entry");
    m.put("signal_id", "chat-messages-" + SECRET);
    m.put("strategy_id", "copytrade-v1");
    m.put("broker_target", "alpaca-paper");
    m.put("option_symbol", contract);
    m.put("side", side);
    m.put("qty", 11L);
    m.put("state", "FILLED");
    m.put("broker_order_id", "b-1");
    m.put("recorded_at", at);
    m.put("last_error", SECRET + " broker text");
    return m;
  }

  private static Map<String, Object> fill(String contract, OffsetDateTime at) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("event_id", SECRET);
    m.put("occurred_at", at);
    m.put("kind", "EntryFilled");
    m.put("actor", SECRET + "@example.com");
    m.put("strategy_id", "copytrade-v1");
    m.put("workflow_id", SECRET);
    m.put("correlation_id", SECRET);
    m.put(
        "subject",
        "{\"signal_id\":\""
            + SECRET
            + "\",\"option_symbol\":\""
            + contract
            + "\",\"filled_qty\":11,\"avg_fill_price\":3.36,\"broker_order_id\":\"b-1\"}");
    m.put("entry_basis", new BigDecimal("3.36"));
    return m;
  }

  private static Map<String, Object> positionRow(String contract) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("workflow_id", SECRET);
    p.put("strategy_id", "copytrade-v1");
    p.put("contract_symbol", contract);
    p.put("remaining_qty", 11L);
    p.put("entry_premium", new BigDecimal("3.36"));
    p.put("open_notional", new BigDecimal("3696.00"));
    return p;
  }

  private static Map<String, Object> portfolioBody(List<Map<String, Object>> rows) {
    Map<String, Object> equity = new LinkedHashMap<>();
    equity.put("broker_target", "alpaca-paper");
    equity.put("equity", new BigDecimal("96591.36"));
    equity.put("account_number", SECRET);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tenant_id", SECRET);
    body.put("trading_day", "2026-11-27");
    body.put("open_positions", rows);
    body.put("account_equity", List.of(equity));
    return body;
  }

  /** NVDA: a 40-minute-old open position. GOOGL: bought 2 minutes ago, position just opened. */
  private void oneOldOneFresh(String tenant) {
    when(positions.openPositions(tenant))
        .thenReturn(
            List.of(
                open(tenant, "copytrade-v1", NVDA, "sig-old"),
                open(tenant, "copytrade-v1", GOOGL, "sig-new")));
    when(entryTimes.firstEntryAt(eq(tenant), any()))
        .thenReturn(
            Map.of("sig-old", minutesAgo(40).toInstant(), "sig-new", minutesAgo(2).toInstant()));
    when(ordersReader.orders(eq(tenant), anyInt()))
        .thenReturn(
            List.of(
                order(GOOGL, "BUY", minutesAgo(2)),
                order(NVDA, "BUY", minutesAgo(40)),
                order(NVDA, "BUY", minutesAgo(1)))); // adding to the visible NVDA position
  }

  @Test
  void missingTenantHeader_is401() throws Exception {
    mvc.perform(get("/api/preview/portfolio")).andExpect(status().isUnauthorized());
  }

  @Test
  void postIsNotMapped() throws Exception {
    mvc.perform(post("/api/preview/portfolio").header("X-Tenant-Id", "t-post"))
        .andExpect(status().isMethodNotAllowed());
  }

  @Test
  void portfolio_hidesTheFreshEntry_showsTheOldPosition_andLeaksNothing() throws Exception {
    oneOldOneFresh("t-portfolio");
    when(portfolioCache.portfolio("t-portfolio"))
        .thenReturn(portfolioBody(List.of(positionRow(NVDA), positionRow(GOOGL))));

    String body =
        mvc.perform(get("/api/preview/portfolio").header("X-Tenant-Id", "t-portfolio"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.open_positions_count").value(1))
            .andExpect(jsonPath("$.open_positions[0].contract_symbol").value(NVDA))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain(SECRET).doesNotContain("GOOGL");
  }

  @Test
  void fills_hideTheFreshEntry_showAddsToTheVisiblePosition_andLeakNothing() throws Exception {
    oneOldOneFresh("t-fills");
    when(tradesReader.trades(eq("t-fills"), anyList(), anyString(), anyInt()))
        .thenReturn(
            List.of(
                fill(GOOGL, minutesAgo(2)), fill(NVDA, minutesAgo(1)), fill(NVDA, minutesAgo(40))));

    String body =
        mvc.perform(get("/api/preview/fills").header("X-Tenant-Id", "t-fills"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(2))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain(SECRET).doesNotContain("GOOGL");
    verifyNoInteractions(portfolioCache); // the fast endpoints must never start a broker workflow
  }

  @Test
  void fills_askOnlyForTheShownStrategies_andTheThirtyDayWindow() throws Exception {
    oneOldOneFresh("t-fills-args");
    when(tradesReader.trades(anyString(), anyList(), anyString(), anyInt())).thenReturn(List.of());

    mvc.perform(get("/api/preview/fills").header("X-Tenant-Id", "t-fills-args"))
        .andExpect(status().isOk());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> strategies = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<String> since = ArgumentCaptor.forClass(String.class);
    verify(tradesReader)
        .trades(eq("t-fills-args"), strategies.capture(), since.capture(), anyInt());
    assertThat(strategies.getValue())
        .containsExactlyInAnyOrder("copytrade-v1", "copytrade-lotto-v1", "watchlist-trigger-v1");
    assertThat(OffsetDateTime.parse(since.getValue()).toInstant())
        .isEqualTo(NOW.minus(Duration.ofDays(30)));
  }

  @Test
  void orders_hideTheFreshEntry_andLeakNothing() throws Exception {
    oneOldOneFresh("t-orders");

    String body =
        mvc.perform(get("/api/preview/orders").header("X-Tenant-Id", "t-orders"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(2))
            .andExpect(jsonPath("$.items[0].failed").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain(SECRET).doesNotContain("GOOGL");
    verifyNoInteractions(portfolioCache);
  }

  @Test
  void marks_requestOnlyVisibleContracts_andReturnOnlyNamedFields() throws Exception {
    oneOldOneFresh("t-marks");
    Map<String, Object> mark = new LinkedHashMap<>();
    mark.put("occ", NVDA_COMPACT);
    mark.put("bid", 0.9);
    mark.put("some_future_field", SECRET);
    when(marksClient.marks(anyList()))
        .thenReturn(new MarksResponse("2026-11-27T15:00:00Z", List.of(mark)));

    String body =
        mvc.perform(get("/api/preview/marks").header("X-Tenant-Id", "t-marks"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.marks.length()").value(1))
            .andExpect(jsonPath("$.marks[0].occ").value(NVDA))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain(SECRET);
    // The quarantined contract is never even sent to market-data.
    verify(marksClient).marks(List.of(NVDA_COMPACT));
    verify(marksClient, never()).marks(List.of(GOOGL_COMPACT));
    verifyNoInteractions(portfolioCache);
  }

  @Test
  void openPositions_areReadOncePerTtl_acrossEndpoints() throws Exception {
    oneOldOneFresh("t-cache");
    when(tradesReader.trades(anyString(), anyList(), anyString(), anyInt())).thenReturn(List.of());

    mvc.perform(get("/api/preview/fills").header("X-Tenant-Id", "t-cache"));
    mvc.perform(get("/api/preview/orders").header("X-Tenant-Id", "t-cache"));
    mvc.perform(get("/api/preview/orders").header("X-Tenant-Id", "t-cache"));

    verify(positions, times(1)).openPositions("t-cache");
  }

  @Test
  void whenTheQuarantineInputsCannotBeRead_nothingIsServed() throws Exception {
    when(positions.openPositions("t-down")).thenThrow(new IllegalStateException("temporal down"));
    when(ordersReader.orders(eq("t-down"), anyInt())).thenReturn(List.of());
    when(portfolioCache.portfolio("t-down")).thenReturn(portfolioBody(List.of(positionRow(NVDA))));

    for (String path : new String[] {"portfolio", "fills", "orders", "marks"}) {
      mvc.perform(get("/api/preview/" + path).header("X-Tenant-Id", "t-down"))
          .andExpect(status().is5xxServerError());
    }
  }

  @Test
  void status_carriesStateAndTimes_neverTheReason() throws Exception {
    KillSwitchState s = new KillSwitchState();
    s.setSchemaVersion(1L);
    s.setTripped(true);
    s.setReason(SECRET + " free text");
    s.setActor("operator:" + SECRET);
    s.setTrippedAt(OffsetDateTime.parse("2026-11-27T14:00:00Z"));
    WorkflowStub stub = mock(WorkflowStub.class);
    when(workflowClient.newUntypedWorkflowStub(WorkflowIds.accountKillswitch("t-status")))
        .thenReturn(stub);
    when(stub.query("account_killswitch_state", KillSwitchState.class)).thenReturn(s);

    String body =
        mvc.perform(get("/api/preview/status").header("X-Tenant-Id", "t-status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tripped").value(true))
            .andExpect(jsonPath("$.resettableAt").value("2026-11-27T14:15Z"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain(SECRET).doesNotContain("reason");
  }
}
