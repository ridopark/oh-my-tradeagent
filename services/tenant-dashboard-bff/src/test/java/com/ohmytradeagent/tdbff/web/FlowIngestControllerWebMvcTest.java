package com.ohmytradeagent.tdbff.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.flow.FlowRepository;
import com.ohmytradeagent.tdbff.flow.FlowRepository.IngestResult;
import com.ohmytradeagent.tdbff.flow.FlowRepository.Order;
import com.ohmytradeagent.tdbff.flow.FlowRepository.TickerDay;
import com.ohmytradeagent.tdbff.security.ServiceTokenFilter;
import com.ohmytradeagent.tdbff.watchlist.WatchlistDailyReader;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.context.NestedTestConfiguration.EnclosingConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer contract for {@code POST /internal/flow/ingest} and {@code GET
 * /internal/flow/watchlist}. Runs the REAL {@link ServiceTokenFilter}: the routes accept only the
 * ingest token, and the traversal guard must hold for the third prefix exactly as it does for the
 * other two.
 */
@WebMvcTest({FlowIngestController.class, FlowInternalController.class})
@Import(ServiceTokenFilter.class)
@TestPropertySource(
    properties = {
      "flow.enabled=true",
      "dashboard.writer.enabled=true",
      "bff.service-token=test-shared-token",
      "options-chat.ingest-token=test-ingest-token"
    })
class FlowIngestControllerWebMvcTest {

  private static final String INGEST = "Bearer test-ingest-token";
  private static final String SHARED = "Bearer test-shared-token";

  /** snake_case on the wire — identical to the V16 columns (plan wire contract). */
  private static final String ONE_ORDER_ONE_DAY =
      """
      {"orders":[
        {"order_id":"AAPL240119C00200000:1706283660000000000:buy","ticker":"AAPL",
         "contract":"AAPL240119C00200000","expiry":"2024-01-19","strike":200.0,
         "right":"C","first_ts":"2026-10-31T14:01:00Z","last_ts":"2026-10-31T14:01:00.500Z",
         "trade_count":3,"exchange_count":2,"size":100,"premium":50000.00,"vwap":5.0,
         "side":"buy","side_source":"quote","bid":4.9,"ask":5.1,
         "quote_ts":"2026-10-31T14:00:59Z","sweep":false,"multi_leg":false,
         "auction":false,"cross":false,"block":false,"unusual":null,
         "day_volume":null,"open_interest":null,"underlying_price":null,
         "moneyness_pct":null,"dte":80,"conditions":"@",
         "block_premium_threshold":250000.00,"block_size_threshold":1000,
         "order_window_ms":1000,"quote_max_age_ms":3000}
      ],
      "ticker_days":[
        {"ticker":"AAPL","session_date":"2026-10-31","call_premium":50000.00,
         "put_premium":0,"bought_premium":50000.00,"sold_premium":0,
         "mid_premium":0,"unknown_premium":0,"trade_count":3,"order_count":1,
         "quoted_premium_share":1.0}
      ]}""";

  @Autowired private MockMvc mvc;
  @MockitoBean private FlowRepository repo;
  @MockitoBean private WatchlistDailyReader watchlistReader;

  @Test
  void noToken_is401_andNothingIsWritten() throws Exception {
    mvc.perform(
            post("/internal/flow/ingest")
                .contentType("application/json")
                .content(ONE_ORDER_ONE_DAY))
        .andExpect(status().isUnauthorized());
    verify(repo, never()).ingest(anyList(), anyList());
  }

  @Test
  void sharedToken_is401() throws Exception {
    mvc.perform(
            post("/internal/flow/ingest")
                .header("Authorization", SHARED)
                .contentType("application/json")
                .content(ONE_ORDER_ONE_DAY))
        .andExpect(status().isUnauthorized());
    verify(repo, never()).ingest(anyList(), anyList());
  }

  @Test
  void traversalUnderTheFlowPrefix_isRejected() throws Exception {
    mvc.perform(
            post(URI.create("/internal/flow/%2e%2e/api/positions"))
                .header("Authorization", INGEST)
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void validBatch_storesAndReportsCounts() throws Exception {
    when(repo.ingest(anyList(), anyList())).thenReturn(new IngestResult(1, 1));

    mvc.perform(
            post("/internal/flow/ingest")
                .header("Authorization", INGEST)
                .contentType("application/json")
                .content(ONE_ORDER_ONE_DAY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stored_orders").value(1))
        .andExpect(jsonPath("$.stored_ticker_days").value(1));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Order>> orders = ArgumentCaptor.forClass(List.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<TickerDay>> days = ArgumentCaptor.forClass(List.class);
    verify(repo).ingest(orders.capture(), days.capture());

    assertThat(orders.getValue()).hasSize(1);
    Order o = orders.getValue().get(0);
    assertThat(o.orderId()).isEqualTo("AAPL240119C00200000:1706283660000000000:buy");
    assertThat(o.ticker()).isEqualTo("AAPL");
    assertThat(o.right()).isEqualTo("C");
    assertThat(o.side()).isEqualTo("buy");
    assertThat(o.sideSource()).isEqualTo("quote");
    assertThat(o.sweep()).isFalse();
    assertThat(o.unusual()).isNull();

    assertThat(days.getValue()).hasSize(1);
    TickerDay d = days.getValue().get(0);
    assertThat(d.ticker()).isEqualTo("AAPL");
    assertThat(d.sessionDate()).isEqualTo(LocalDate.of(2026, 10, 31));
    assertThat(d.orderCount()).isEqualTo(1);
  }

  @Test
  void moreThan5000Orders_is400() throws Exception {
    String order =
        "{\"order_id\":\"%d\",\"ticker\":\"X\",\"contract\":\"X240119C00010000\","
            + "\"expiry\":\"2024-01-19\",\"strike\":10.0,\"right\":\"C\","
            + "\"first_ts\":\"2026-10-31T14:00:00Z\",\"last_ts\":\"2026-10-31T14:00:00Z\","
            + "\"trade_count\":1,\"exchange_count\":1,\"size\":1,\"premium\":1000.00,"
            + "\"vwap\":1.0,\"side\":\"buy\",\"side_source\":\"none\","
            + "\"sweep\":false,\"multi_leg\":false,\"auction\":false,\"cross\":false,"
            + "\"block\":false,\"dte\":1,"
            + "\"block_premium_threshold\":250000,\"block_size_threshold\":1000,"
            + "\"order_window_ms\":1000,\"quote_max_age_ms\":3000}";
    StringBuilder body = new StringBuilder("{\"orders\":[");
    for (int i = 0; i < 5001; i++) {
      if (i > 0) body.append(',');
      body.append(String.format(order, i));
    }
    body.append("],\"ticker_days\":[]}");

    mvc.perform(
            post("/internal/flow/ingest")
                .header("Authorization", INGEST)
                .contentType("application/json")
                .content(body.toString()))
        .andExpect(status().isBadRequest());
    verify(repo, never()).ingest(anyList(), anyList());
  }

  @Test
  void anEmptyBatch_isAccepted() throws Exception {
    when(repo.ingest(anyList(), anyList())).thenReturn(new IngestResult(0, 0));

    mvc.perform(
            post("/internal/flow/ingest")
                .header("Authorization", INGEST)
                .contentType("application/json")
                .content("{\"orders\":[],\"ticker_days\":[]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stored_orders").value(0))
        .andExpect(jsonPath("$.stored_ticker_days").value(0));
  }

  @Test
  void anInvalidSide_is400() throws Exception {
    mvc.perform(
            post("/internal/flow/ingest")
                .header("Authorization", INGEST)
                .contentType("application/json")
                .content(ONE_ORDER_ONE_DAY.replace("\"side\":\"buy\"", "\"side\":\"long\"")))
        .andExpect(status().isBadRequest());
    verify(repo, never()).ingest(anyList(), anyList());
  }

  @Test
  void aMissingOrderId_is400() throws Exception {
    mvc.perform(
            post("/internal/flow/ingest")
                .header("Authorization", INGEST)
                .contentType("application/json")
                .content(
                    ONE_ORDER_ONE_DAY.replace(
                        "\"order_id\":\"AAPL240119C00200000:1706283660000000000:buy\",", "")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("invalid or missing order.order_id"));
    verify(repo, never()).ingest(anyList(), anyList());
  }

  @Test
  void watchlist_withIngestToken_returnsDateAndTickers() throws Exception {
    when(watchlistReader.ideasFor(LocalDate.of(2026, 10, 31)))
        .thenReturn(
            List.of(
                new WatchlistDailyReader.Idea("AAPL", "C", null, "ABOVE", null),
                new WatchlistDailyReader.Idea("NVDA", "P", null, "BELOW", null),
                new WatchlistDailyReader.Idea("AAPL", "P", null, "BELOW", null)));

    mvc.perform(
            get("/internal/flow/watchlist")
                .header("Authorization", INGEST)
                .param("date", "2026-10-31"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.date").value("2026-10-31"))
        // Distinct sorted tickers: AAPL and NVDA
        .andExpect(jsonPath("$.tickers[0]").value("AAPL"))
        .andExpect(jsonPath("$.tickers[1]").value("NVDA"))
        .andExpect(jsonPath("$.tickers.length()").value(2));
  }

  @Test
  void watchlist_withSharedToken_is401() throws Exception {
    mvc.perform(
            get("/internal/flow/watchlist")
                .header("Authorization", SHARED)
                .param("date", "2026-10-31"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void watchlist_noWatchlistThatDay_returnsEmptyTickers() throws Exception {
    when(watchlistReader.ideasFor(LocalDate.of(2026, 10, 31))).thenReturn(null);

    mvc.perform(
            get("/internal/flow/watchlist")
                .header("Authorization", INGEST)
                .param("date", "2026-10-31"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tickers.length()").value(0));
  }

  /** Dark: both routes are absent (404) while the feature flag is off. */
  @Nested
  @NestedTestConfiguration(EnclosingConfiguration.OVERRIDE)
  @WebMvcTest({FlowIngestController.class, FlowInternalController.class})
  @AutoConfigureMockMvc(addFilters = false)
  @TestPropertySource(properties = {"flow.enabled=false", "dashboard.writer.enabled=true"})
  class FeatureFlagOff {
    @Autowired private MockMvc mvc;
    @MockitoBean private FlowRepository repo;
    @MockitoBean private WatchlistDailyReader watchlistReader;

    @Test
    void ingestRouteIs404() throws Exception {
      mvc.perform(
              post("/internal/flow/ingest")
                  .contentType("application/json")
                  .content("{\"orders\":[],\"ticker_days\":[]}"))
          .andExpect(status().isNotFound());
    }

    @Test
    void watchlistRouteIs404() throws Exception {
      mvc.perform(get("/internal/flow/watchlist")).andExpect(status().isNotFound());
    }
  }

  /** The writer flag off: the repository bean is absent, so the routes must be too. */
  @Nested
  @NestedTestConfiguration(EnclosingConfiguration.OVERRIDE)
  @WebMvcTest({FlowIngestController.class, FlowInternalController.class})
  @AutoConfigureMockMvc(addFilters = false)
  @TestPropertySource(properties = {"flow.enabled=true", "dashboard.writer.enabled=false"})
  class WriterFlagOff {
    @Autowired private MockMvc mvc;
    @MockitoBean private FlowRepository repo;
    @MockitoBean private WatchlistDailyReader watchlistReader;

    @Test
    void ingestRouteIs404() throws Exception {
      mvc.perform(
              post("/internal/flow/ingest")
                  .contentType("application/json")
                  .content("{\"orders\":[],\"ticker_days\":[]}"))
          .andExpect(status().isNotFound());
    }
  }
}
