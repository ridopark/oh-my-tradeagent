package com.ohmytradeagent.tdbff.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.flow.FlowRepository;
import com.ohmytradeagent.tdbff.flow.FlowRepository.Order;
import com.ohmytradeagent.tdbff.flow.FlowRepository.OrderQuery;
import com.ohmytradeagent.tdbff.flow.FlowRepository.TickerDay;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
 * Web-layer contract for {@code GET /api/flow/orders} and {@code GET /api/flow/summary}. Filters
 * are off; the bearer 401 is proven in {@link ServiceTokenFilterTest}. Like darkpool, the data is
 * tenant-independent but a signed-in session ({@code X-Tenant-Id}) is still required.
 */
@WebMvcTest(FlowController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
@TestPropertySource(properties = {"flow.enabled=true", "dashboard.writer.enabled=true"})
class FlowControllerWebMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private FlowRepository repo;

  private static Order order() {
    return new Order(
        "AAPL240119C00200000:1706283660000000000:buy",
        "AAPL",
        "AAPL240119C00200000",
        LocalDate.of(2024, 1, 19),
        new BigDecimal("200.00"),
        "C",
        OffsetDateTime.parse("2026-10-31T14:01:00Z"),
        OffsetDateTime.parse("2026-10-31T14:01:00.500Z"),
        3,
        2,
        100,
        new BigDecimal("50000.00"),
        new BigDecimal("5.0000"),
        "buy",
        "quote",
        new BigDecimal("4.9000"),
        new BigDecimal("5.1000"),
        OffsetDateTime.parse("2026-10-31T14:00:59Z"),
        false,
        false,
        false,
        false,
        false,
        null,
        null,
        null,
        null,
        null,
        80,
        "@",
        new BigDecimal("250000.00"),
        1000,
        1000,
        3000);
  }

  private static TickerDay tickerDay() {
    return new TickerDay(
        "AAPL",
        LocalDate.of(2026, 10, 31),
        new BigDecimal("50000.00"),
        BigDecimal.ZERO,
        new BigDecimal("50000.00"),
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        3,
        1,
        new BigDecimal("1.0"));
  }

  @Test
  void missingTenantHeaderIs401_beforeAnyRead() throws Exception {
    mvc.perform(get("/api/flow/orders")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/flow/summary")).andExpect(status().isUnauthorized());
    verifyNoInteractions(repo);
  }

  @Test
  void orders_passesFiltersThrough_andRendersTheRow() throws Exception {
    when(repo.orders(any())).thenReturn(List.of(order()));

    mvc.perform(
            get("/api/flow/orders")
                .header("X-Tenant-Id", "prod_real")
                .param("date", "2026-10-31")
                .param("ticker", "AAPL")
                .param("min_premium", "10000")
                .param("right", "C")
                .param("side", "buy")
                .param("dte_min", "30")
                .param("dte_max", "90")
                .param("limit", "50"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.date").value("2026-10-31"))
        .andExpect(jsonPath("$.count").value(1))
        .andExpect(
            jsonPath("$.items[0].order_id").value("AAPL240119C00200000:1706283660000000000:buy"))
        .andExpect(jsonPath("$.items[0].ticker").value("AAPL"))
        .andExpect(jsonPath("$.items[0].contract").value("AAPL240119C00200000"))
        .andExpect(jsonPath("$.items[0].expiry").value("2024-01-19"))
        .andExpect(jsonPath("$.items[0].right").value("C"))
        .andExpect(jsonPath("$.items[0].first_ts").value("2026-10-31T14:01:00Z"))
        .andExpect(jsonPath("$.items[0].last_ts").value("2026-10-31T14:01:00.500Z"))
        .andExpect(jsonPath("$.items[0].side").value("buy"))
        .andExpect(jsonPath("$.items[0].side_source").value("quote"))
        .andExpect(jsonPath("$.items[0].sweep").value(false))
        .andExpect(jsonPath("$.items[0].unusual").value(nullValue()))
        .andExpect(jsonPath("$.items[0].dte").value(80))
        .andExpect(jsonPath("$.items[0].block_premium_threshold").value(250000.0))
        .andExpect(jsonPath("$.items[0].block_size_threshold").value(1000))
        .andExpect(jsonPath("$.items[0].order_window_ms").value(1000))
        .andExpect(jsonPath("$.items[0].quote_max_age_ms").value(3000));

    ArgumentCaptor<OrderQuery> q = ArgumentCaptor.forClass(OrderQuery.class);
    verify(repo).orders(q.capture());
    assertThat(q.getValue().ticker()).isEqualTo("AAPL");
    assertThat(q.getValue().right()).isEqualTo("C");
    assertThat(q.getValue().side()).isEqualTo("buy");
    assertThat(q.getValue().dteMin()).isEqualTo(30);
    assertThat(q.getValue().dteMax()).isEqualTo(90);
    assertThat(q.getValue().limit()).isEqualTo(50);
    assertThat(q.getValue().date()).isEqualTo(LocalDate.of(2026, 10, 31));
    assertThat(q.getValue().minPremium()).isEqualByComparingTo("10000");
  }

  @Test
  void orders_labelsFilter_parsesCommaSeparatedValues() throws Exception {
    when(repo.orders(any())).thenReturn(List.of());

    mvc.perform(get("/api/flow/orders").header("X-Tenant-Id", "t").param("labels", "sweep,block"))
        .andExpect(status().isOk());

    ArgumentCaptor<OrderQuery> q = ArgumentCaptor.forClass(OrderQuery.class);
    verify(repo).orders(q.capture());
    assertThat(q.getValue().labels()).containsExactlyInAnyOrder("sweep", "block");
  }

  @Test
  void orders_labelsFilter_silentlyDropsUnknownValues() throws Exception {
    when(repo.orders(any())).thenReturn(List.of());

    mvc.perform(
            get("/api/flow/orders").header("X-Tenant-Id", "t").param("labels", "sweep,badlabel"))
        .andExpect(status().isOk());

    ArgumentCaptor<OrderQuery> q = ArgumentCaptor.forClass(OrderQuery.class);
    verify(repo).orders(q.capture());
    assertThat(q.getValue().labels()).containsExactly("sweep");
  }

  @Test
  void orders_rendersTimestampsAsIsoInstants_evenAtZeroSeconds() throws Exception {
    Order onTheMinute =
        new Order(
            "id1",
            "SPY",
            "SPY240101P00500000",
            null,
            new BigDecimal("500.00"),
            "P",
            OffsetDateTime.parse("2026-10-31T14:00:00Z"),
            OffsetDateTime.parse("2026-10-31T14:00:00Z"),
            1,
            1,
            10,
            new BigDecimal("500.00"),
            new BigDecimal("0.5000"),
            "sell",
            "none",
            null,
            null,
            OffsetDateTime.parse("2026-10-31T14:00:00+00:00"),
            false,
            false,
            false,
            false,
            false,
            null,
            null,
            null,
            null,
            null,
            1,
            null,
            new BigDecimal("250000.00"),
            1000,
            1000,
            3000);
    when(repo.orders(any())).thenReturn(List.of(onTheMinute));

    mvc.perform(get("/api/flow/orders").header("X-Tenant-Id", "t"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].first_ts").value("2026-10-31T14:00:00Z"))
        .andExpect(jsonPath("$.items[0].last_ts").value("2026-10-31T14:00:00Z"))
        .andExpect(jsonPath("$.items[0].quote_ts").value("2026-10-31T14:00:00Z"));
  }

  @Test
  void orders_defaultsAndClampsTheLimit() throws Exception {
    when(repo.orders(any())).thenReturn(List.of());

    mvc.perform(get("/api/flow/orders").header("X-Tenant-Id", "t")).andExpect(status().isOk());
    ArgumentCaptor<OrderQuery> q1 = ArgumentCaptor.forClass(OrderQuery.class);
    verify(repo).orders(q1.capture());
    assertThat(q1.getValue().limit()).isEqualTo(200);

    when(repo.orders(any())).thenReturn(List.of());
    mvc.perform(get("/api/flow/orders").header("X-Tenant-Id", "t").param("limit", "9999"))
        .andExpect(status().isOk());
    ArgumentCaptor<OrderQuery> q2 = ArgumentCaptor.forClass(OrderQuery.class);
    verify(repo, org.mockito.Mockito.times(2)).orders(q2.capture());
    assertThat(q2.getValue().limit()).isEqualTo(500);
  }

  @Test
  void orders_badDate_is400() throws Exception {
    mvc.perform(get("/api/flow/orders").header("X-Tenant-Id", "t").param("date", "yesterday"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(repo);
  }

  @Test
  void orders_badRight_is400() throws Exception {
    mvc.perform(get("/api/flow/orders").header("X-Tenant-Id", "t").param("right", "X"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(repo);
  }

  @Test
  void orders_badSide_is400() throws Exception {
    mvc.perform(get("/api/flow/orders").header("X-Tenant-Id", "t").param("side", "long"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(repo);
  }

  @Test
  void orders_negativePremium_is400() throws Exception {
    mvc.perform(get("/api/flow/orders").header("X-Tenant-Id", "t").param("min_premium", "-1"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(repo);
  }

  @Test
  void orders_badTicker_is400() throws Exception {
    mvc.perform(
            get("/api/flow/orders").header("X-Tenant-Id", "t").param("ticker", "QQQ; DROP TABLE"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(repo);
  }

  @Test
  void orders_cursorPaging_passesBeforeThrough() throws Exception {
    when(repo.orders(any())).thenReturn(List.of());

    mvc.perform(
            get("/api/flow/orders")
                .header("X-Tenant-Id", "t")
                .param("before", "2026-10-31T15:30:00Z"))
        .andExpect(status().isOk());

    ArgumentCaptor<OrderQuery> q = ArgumentCaptor.forClass(OrderQuery.class);
    verify(repo).orders(q.capture());
    assertThat(q.getValue().before()).isEqualTo(OffsetDateTime.parse("2026-10-31T15:30:00Z"));
  }

  @Test
  void orders_noDateFilter_hasNoDateInResponse() throws Exception {
    when(repo.orders(any())).thenReturn(List.of());

    mvc.perform(get("/api/flow/orders").header("X-Tenant-Id", "t"))
        .andExpect(status().isOk())
        // When no date filter is applied, the date key is absent from the response body.
        .andExpect(jsonPath("$.date").doesNotExist())
        .andExpect(jsonPath("$.count").value(0))
        .andExpect(jsonPath("$.items").isArray());
  }

  @Test
  void summary_rendersPerTickerShapeForTheRequestedSessionDate() throws Exception {
    when(repo.summary(LocalDate.of(2026, 10, 31), "AAPL")).thenReturn(List.of(tickerDay()));

    mvc.perform(
            get("/api/flow/summary")
                .header("X-Tenant-Id", "prod_real")
                .param("date", "2026-10-31")
                .param("ticker", "AAPL"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.date").value("2026-10-31"))
        .andExpect(jsonPath("$.count").value(1))
        .andExpect(jsonPath("$.tickers[0].ticker").value("AAPL"))
        .andExpect(jsonPath("$.tickers[0].session_date").value("2026-10-31"))
        .andExpect(jsonPath("$.tickers[0].call_premium").value(50000.0))
        .andExpect(jsonPath("$.tickers[0].put_premium").value(0))
        .andExpect(jsonPath("$.tickers[0].bought_premium").value(50000.0))
        .andExpect(jsonPath("$.tickers[0].sold_premium").value(0))
        .andExpect(jsonPath("$.tickers[0].trade_count").value(3))
        .andExpect(jsonPath("$.tickers[0].order_count").value(1))
        .andExpect(jsonPath("$.tickers[0].quoted_premium_share").value(1.0));
  }

  @Test
  void summary_defaultsToTodayInEasternTime() throws Exception {
    when(repo.summary(any(), any())).thenReturn(List.of());

    LocalDate before = LocalDate.now(ZoneId.of("America/New_York"));
    mvc.perform(get("/api/flow/summary").header("X-Tenant-Id", "t"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(0));
    LocalDate after = LocalDate.now(ZoneId.of("America/New_York"));

    ArgumentCaptor<LocalDate> used = ArgumentCaptor.forClass(LocalDate.class);
    verify(repo).summary(used.capture(), any());
    assertThat(used.getValue()).isIn(before, after);
  }

  @Test
  void summary_badDate_is400() throws Exception {
    mvc.perform(get("/api/flow/summary").header("X-Tenant-Id", "t").param("date", "today"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(repo);
  }

  @Test
  void summary_badTicker_is400() throws Exception {
    mvc.perform(
            get("/api/flow/summary").header("X-Tenant-Id", "t").param("ticker", "AAPL; DROP TABLE"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(repo);
  }

  private static org.hamcrest.Matcher<Object> nullValue() {
    return org.hamcrest.Matchers.nullValue();
  }

  /** Dark: both reads are absent (404) while the feature flag is off. */
  @Nested
  @NestedTestConfiguration(EnclosingConfiguration.OVERRIDE)
  @WebMvcTest(FlowController.class)
  @AutoConfigureMockMvc(addFilters = false)
  @Import(TenantContext.class)
  @TestPropertySource(properties = {"flow.enabled=false", "dashboard.writer.enabled=true"})
  class FeatureFlagOff {
    @Autowired private MockMvc mvc;
    @MockitoBean private FlowRepository repo;

    @Test
    void bothReadsAre404() throws Exception {
      mvc.perform(get("/api/flow/orders").header("X-Tenant-Id", "prod_real"))
          .andExpect(status().isNotFound());
      mvc.perform(get("/api/flow/summary").header("X-Tenant-Id", "prod_real"))
          .andExpect(status().isNotFound());
    }
  }

  /** The writer flag off: the repository bean is absent, so the reads must be too. */
  @Nested
  @NestedTestConfiguration(EnclosingConfiguration.OVERRIDE)
  @WebMvcTest(FlowController.class)
  @AutoConfigureMockMvc(addFilters = false)
  @Import(TenantContext.class)
  @TestPropertySource(properties = {"flow.enabled=true", "dashboard.writer.enabled=false"})
  class WriterFlagOff {
    @Autowired private MockMvc mvc;
    @MockitoBean private FlowRepository repo;

    @Test
    void bothReadsAre404() throws Exception {
      mvc.perform(get("/api/flow/orders").header("X-Tenant-Id", "prod_real"))
          .andExpect(status().isNotFound());
      mvc.perform(get("/api/flow/summary").header("X-Tenant-Id", "prod_real"))
          .andExpect(status().isNotFound());
    }
  }
}
