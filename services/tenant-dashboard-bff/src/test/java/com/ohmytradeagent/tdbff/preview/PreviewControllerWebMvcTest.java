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
import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader;
import com.ohmytradeagent.tdbff.platform.TenantStrategyResolver;
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
  @MockitoBean private TenantStrategyResolver strategyResolver;
  @MockitoBean private DbStrategyConfigReader strategyRegistry;

  @BeforeEach
  void fixClock() {
    service.clock = Clock.fixed(NOW, ZoneOffset.UTC);
    when(strategyResolver.strategyIdsForTenant(anyString())).thenReturn(List.of("copytrade-v1"));
    when(strategyRegistry.brokerTarget(anyString(), anyString())).thenReturn("alpaca-paper");
  }

  private static String key(String signalId, String contract) {
    return PreviewEntryTimes.key(signalId, contract);
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
    m.put("broker_order_id", "11111111-2222-4333-8444-555555555555");
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
            + "\",\"filled_qty\":11,\"avg_fill_price\":3.36,\"broker_order_id\":\"11111111-2222-4333-8444-555555555555\"}");
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
    when(entryTimes.entryAt(eq(tenant), any()))
        .thenReturn(
            Map.of(
                key("sig-old", NVDA),
                minutesAgo(40).toInstant(),
                key("sig-new", GOOGL),
                minutesAgo(2).toInstant()));
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

  // ---- hardening found in security review ---------------------------------------------------

  @Test
  void marks_nestedUnderlying_carriesOnlyNamedFields() throws Exception {
    oneOldOneFresh("t-marks-nested");
    Map<String, Object> underlying = new LinkedHashMap<>();
    underlying.put("ticker", "NVDA");
    underlying.put("price", 181.5);
    underlying.put("at", "2026-11-27T15:00:00Z");
    underlying.put("some_future_field", SECRET);
    Map<String, Object> mark = new LinkedHashMap<>();
    mark.put("occ", NVDA_COMPACT);
    mark.put("bid", 0.9);
    mark.put("underlying", underlying);
    when(marksClient.marks(anyList()))
        .thenReturn(new MarksResponse("2026-11-27T15:00:00Z", List.of(mark)));

    String body =
        mvc.perform(get("/api/preview/marks").header("X-Tenant-Id", "t-marks-nested"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.marks[0].underlying.ticker").value("NVDA"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain(SECRET);
  }

  @Test
  void anOrderSideThatIsNotSell_countsAsAnEntry() throws Exception {
    // Fail-closed: an unfamiliar side value must hide the contract, not reveal it.
    for (String side : new String[] {"buy_to_open", "BTO", "", "MLEG"}) {
      String tenant = "t-side-" + side.toLowerCase(java.util.Locale.ROOT);
      when(positions.openPositions(tenant)).thenReturn(List.of());
      when(entryTimes.entryAt(eq(tenant), any())).thenReturn(Map.of());
      when(ordersReader.orders(eq(tenant), anyInt()))
          .thenReturn(List.of(order(GOOGL, side, minutesAgo(2))));

      mvc.perform(get("/api/preview/orders").header("X-Tenant-Id", tenant))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.count").value(0));
    }
  }

  @Test
  void aSellOrder_doesNotStartAQuarantine() throws Exception {
    when(positions.openPositions("t-sell")).thenReturn(List.of());
    when(entryTimes.entryAt(eq("t-sell"), any())).thenReturn(Map.of());
    when(ordersReader.orders(eq("t-sell"), anyInt()))
        .thenReturn(List.of(order(GOOGL, "SELL", minutesAgo(2))));

    mvc.perform(get("/api/preview/orders").header("X-Tenant-Id", "t-sell"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(1));
  }

  @Test
  void aBuyOrderWithNoTimestamp_hidesItsContract() throws Exception {
    when(positions.openPositions("t-notime")).thenReturn(List.of());
    when(entryTimes.entryAt(eq("t-notime"), any())).thenReturn(Map.of());
    Map<String, Object> noTime = order(GOOGL, "BUY", null);
    when(ordersReader.orders(eq("t-notime"), anyInt()))
        .thenReturn(List.of(noTime, order(GOOGL, "SELL", minutesAgo(1))));

    mvc.perform(get("/api/preview/orders").header("X-Tenant-Id", "t-notime"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(0));
  }

  @Test
  void anAnchorFromTheCache_isRecheckedBeforeItRevealsAFreshBuy() throws Exception {
    // Cached: NVDA open and old. Then that position closes and NVDA is re-entered — the stale
    // cache must not be what makes the new entry visible.
    String tenant = "t-stale-anchor";
    when(positions.openPositions(tenant))
        .thenReturn(List.of(open(tenant, "copytrade-v1", NVDA, "sig-old")))
        .thenReturn(List.of(open(tenant, "copytrade-v1", NVDA, "sig-reentry")));
    when(entryTimes.entryAt(eq(tenant), any()))
        .thenReturn(Map.of(key("sig-old", NVDA), minutesAgo(40).toInstant()))
        .thenReturn(Map.of(key("sig-reentry", NVDA), minutesAgo(1).toInstant()));
    when(ordersReader.orders(eq(tenant), anyInt()))
        .thenReturn(List.of(order(NVDA, "SELL", minutesAgo(30))))
        .thenReturn(
            List.of(order(NVDA, "BUY", minutesAgo(1)), order(NVDA, "SELL", minutesAgo(30))));

    mvc.perform(get("/api/preview/orders").header("X-Tenant-Id", tenant))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(1)); // warms the cache: old position, one sell

    // Ten seconds on: inside the 15s cache TTL, but too old to be trusted to reveal an entry.
    service.clock = Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC);

    mvc.perform(get("/api/preview/orders").header("X-Tenant-Id", tenant))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(1))
        .andExpect(jsonPath("$.items[0].side").value("SELL"));
    verify(positions, times(2)).openPositions(tenant);
  }

  @Test
  void anyFailure_answersAFixedBody_neverTheExceptionMessage() throws Exception {
    when(positions.openPositions("t-leaky"))
        .thenThrow(new IllegalArgumentException("bad workflow id chat-messages-" + SECRET));
    when(ordersReader.orders(eq("t-leaky"), anyInt())).thenReturn(List.of());

    for (String path : new String[] {"portfolio", "fills", "orders", "marks"}) {
      String body =
          mvc.perform(get("/api/preview/" + path).header("X-Tenant-Id", "t-leaky"))
              .andExpect(status().isServiceUnavailable())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(body).isEqualTo("{\"error\":\"unavailable\"}");
    }
  }

  // ---- findings from the independent security review ----------------------------------------

  @Test
  void watchlistLegs_sharingOneSignalId_areDatedPerContract() throws Exception {
    // Every leg of a day's watchlist carries the SAME entry signal id. NVDA filled 40 minutes ago;
    // GOOGL, under that same id, filled 2 minutes ago and must stay hidden.
    String tenant = "t-watchlist";
    when(positions.openPositions(tenant))
        .thenReturn(
            List.of(
                open(tenant, "watchlist-trigger-v1", NVDA, "wl-msg-1"),
                open(tenant, "watchlist-trigger-v1", GOOGL, "wl-msg-1")));
    when(entryTimes.entryAt(eq(tenant), any()))
        .thenReturn(
            Map.of(
                key("wl-msg-1", NVDA),
                minutesAgo(40).toInstant(),
                key("wl-msg-1", GOOGL),
                minutesAgo(2).toInstant()));
    when(ordersReader.orders(eq(tenant), anyInt()))
        .thenReturn(
            List.of(order(GOOGL, "BUY", minutesAgo(2)), order(NVDA, "BUY", minutesAgo(40))));
    when(marksClient.marks(anyList()))
        .thenReturn(new MarksResponse("2026-11-27T15:00:00Z", List.of()));

    String orders =
        mvc.perform(get("/api/preview/orders").header("X-Tenant-Id", tenant))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(1))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(orders).doesNotContain("GOOGL");
    mvc.perform(get("/api/preview/marks").header("X-Tenant-Id", tenant)).andExpect(status().isOk());
    verify(marksClient).marks(List.of(NVDA_COMPACT));
  }

  @Test
  void aPositionInAStrategyTheDemoDoesNotShow_cannotRevealAFreshEntry() throws Exception {
    String tenant = "t-hidden-anchor";
    when(positions.openPositions(tenant))
        .thenReturn(List.of(open(tenant, "gated_condor", GOOGL, "sig-condor")));
    when(entryTimes.entryAt(eq(tenant), any()))
        .thenReturn(Map.of(key("sig-condor", GOOGL), minutesAgo(90).toInstant()));
    when(ordersReader.orders(eq(tenant), anyInt()))
        .thenReturn(List.of(order(GOOGL, "BUY", minutesAgo(2))));

    mvc.perform(get("/api/preview/orders").header("X-Tenant-Id", tenant))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(0));
  }

  @Test
  void aTenantWithAnyNonPaperBrokerTarget_isNeverServed() throws Exception {
    for (String target : new String[] {"alpaca-live", "", "paper"}) {
      String tenant = "t-live-" + target.length();
      when(strategyResolver.strategyIdsForTenant(tenant))
          .thenReturn(List.of("copytrade-v1", "watchlist-trigger-v1"));
      when(strategyRegistry.brokerTarget(tenant, "copytrade-v1")).thenReturn("alpaca-paper");
      when(strategyRegistry.brokerTarget(tenant, "watchlist-trigger-v1"))
          .thenReturn(target.isEmpty() ? null : target);
      when(portfolioCache.portfolio(tenant)).thenReturn(portfolioBody(List.of(positionRow(NVDA))));

      for (String path : new String[] {"status", "portfolio", "fills", "orders", "marks"}) {
        mvc.perform(get("/api/preview/" + path).header("X-Tenant-Id", tenant))
            .andExpect(status().isServiceUnavailable());
      }
      verify(positions, never()).openPositions(tenant);
    }
    verifyNoInteractions(portfolioCache, tradesReader, ordersReader, marksClient, workflowClient);
  }

  @Test
  void portfolioBody_isReadAtMostOncePerThirtySeconds() throws Exception {
    oneOldOneFresh("t-portfolio-ttl");
    when(portfolioCache.portfolio("t-portfolio-ttl"))
        .thenReturn(portfolioBody(List.of(positionRow(NVDA))));

    for (int i = 0; i < 5; i++) {
      mvc.perform(get("/api/preview/portfolio").header("X-Tenant-Id", "t-portfolio-ttl"))
          .andExpect(status().isOk());
    }
    verify(portfolioCache, times(1)).portfolio("t-portfolio-ttl");

    service.clock = Clock.fixed(NOW.plusSeconds(31), ZoneOffset.UTC);
    mvc.perform(get("/api/preview/portfolio").header("X-Tenant-Id", "t-portfolio-ttl"))
        .andExpect(status().isOk());
    verify(portfolioCache, times(2)).portfolio("t-portfolio-ttl");
  }

  @Test
  void moreThanTwoRequestsAtOnce_areRefused_notQueued() throws Exception {
    String tenant = "t-flood";
    java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    when(positions.openPositions(tenant))
        .thenAnswer(
            inv -> {
              entered.countDown();
              release.await(10, java.util.concurrent.TimeUnit.SECONDS);
              return List.of();
            });
    when(entryTimes.entryAt(eq(tenant), any())).thenReturn(Map.of());
    when(ordersReader.orders(eq(tenant), anyInt())).thenReturn(List.of());

    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      java.util.List<java.util.concurrent.Future<Integer>> slow = new java.util.ArrayList<>();
      for (int i = 0; i < 2; i++) {
        slow.add(
            pool.submit(
                () ->
                    mvc.perform(get("/api/preview/orders").header("X-Tenant-Id", tenant))
                        .andReturn()
                        .getResponse()
                        .getStatus()));
      }
      assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      // Both permits are now held (one inside the slow read, one waiting on it).
      long deadline = System.currentTimeMillis() + 5000;
      int third = 0;
      while (System.currentTimeMillis() < deadline) {
        third =
            mvc.perform(get("/api/preview/orders").header("X-Tenant-Id", tenant))
                .andReturn()
                .getResponse()
                .getStatus();
        if (third == 503) {
          break;
        }
        Thread.sleep(20);
      }
      assertThat(third).isEqualTo(503);
      release.countDown();
      for (java.util.concurrent.Future<Integer> f : slow) {
        assertThat(f.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
      }
      // Permits were returned: the next request is served.
      mvc.perform(get("/api/preview/orders").header("X-Tenant-Id", tenant))
          .andExpect(status().isOk());
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }
}
