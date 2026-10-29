package com.ohmytradeagent.tdbff.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.darkpool.DarkpoolRepository;
import com.ohmytradeagent.tdbff.darkpool.DarkpoolRepository.Level;
import com.ohmytradeagent.tdbff.darkpool.DarkpoolRepository.Print;
import com.ohmytradeagent.tdbff.darkpool.DarkpoolRepository.SymbolSummary;
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
 * Web-layer contract for the tenant-session reads {@code GET /api/darkpool/prints} and {@code GET
 * /api/darkpool/summary}. Filters are off; the bearer 401 is proven in {@code
 * ServiceTokenFilterTest}. Like options-chat, the data is tenant-independent but a signed-in
 * session ({@code X-Tenant-Id}) is still required.
 */
@WebMvcTest(DarkpoolController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
@TestPropertySource(properties = {"darkpool.enabled=true", "dashboard.writer.enabled=true"})
class DarkpoolControllerWebMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private DarkpoolRepository repo;

  private static Print print() {
    return new Print(
        52983525029461L,
        "QQQ",
        OffsetDateTime.parse("2026-10-29T18:01:02.123456Z"),
        new BigDecimal("612.3400"),
        2500,
        new BigDecimal("1530850.00"),
        "D",
        "@",
        "B",
        new BigDecimal("612.3300"),
        new BigDecimal("612.3500"),
        OffsetDateTime.parse("2026-10-29T18:01:02.100Z"),
        "buy");
  }

  @Test
  void missingTenantHeaderIs401_beforeAnyRead() throws Exception {
    mvc.perform(get("/api/darkpool/prints")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/darkpool/summary")).andExpect(status().isUnauthorized());
    verifyNoInteractions(repo);
  }

  @Test
  void prints_passesFiltersThrough_andRendersTheRow() throws Exception {
    OffsetDateTime since = OffsetDateTime.parse("2026-10-29T13:30:00Z");
    when(repo.recentPrints(eq("QQQ"), eq(since), eq(new BigDecimal("200000")), eq(50)))
        .thenReturn(List.of(print()));

    mvc.perform(
            get("/api/darkpool/prints")
                .header("X-Tenant-Id", "prod_real")
                .param("symbol", "QQQ")
                .param("since", "2026-10-29T13:30:00Z")
                .param("min_notional", "200000")
                .param("limit", "50"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(1))
        // A string, like the snowflakes: int64 ids are not safe as JavaScript numbers.
        .andExpect(jsonPath("$.items[0].trade_id").value("52983525029461"))
        .andExpect(jsonPath("$.items[0].symbol").value("QQQ"))
        .andExpect(jsonPath("$.items[0].ts").value("2026-10-29T18:01:02.123456Z"))
        .andExpect(jsonPath("$.items[0].price").value(612.34))
        .andExpect(jsonPath("$.items[0].size").value(2500))
        .andExpect(jsonPath("$.items[0].notional").value(1530850.00))
        .andExpect(jsonPath("$.items[0].exchange").value("D"))
        .andExpect(jsonPath("$.items[0].conditions").value("@"))
        .andExpect(jsonPath("$.items[0].tape").value("B"))
        .andExpect(jsonPath("$.items[0].bid").value(612.33))
        .andExpect(jsonPath("$.items[0].ask").value(612.35))
        .andExpect(jsonPath("$.items[0].quote_ts").value("2026-10-29T18:01:02.100Z"))
        .andExpect(jsonPath("$.items[0].side").value("buy"));
  }

  @Test
  void prints_rendersTimestampsAsIsoInstants_evenWithZeroSeconds() throws Exception {
    // OffsetDateTime.toString() would emit "2026-10-29T18:00Z" here — no seconds — and the page
    // would see two shapes for one column. ISO_INSTANT always carries the seconds and a Z.
    Print onTheMinute =
        new Print(
            1L,
            "SPY",
            OffsetDateTime.parse("2026-10-29T18:00:00Z"),
            new BigDecimal("600.0000"),
            100,
            new BigDecimal("60000.00"),
            "D",
            null,
            null,
            null,
            null,
            OffsetDateTime.parse("2026-10-29T17:59:00+00:00"),
            "unknown");
    when(repo.recentPrints(any(), any(), any(), anyInt())).thenReturn(List.of(onTheMinute));

    mvc.perform(get("/api/darkpool/prints").header("X-Tenant-Id", "t"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].ts").value("2026-10-29T18:00:00Z"))
        .andExpect(jsonPath("$.items[0].quote_ts").value("2026-10-29T17:59:00Z"));
  }

  @Test
  void prints_defaultsAndClampsTheLimit() throws Exception {
    when(repo.recentPrints(any(), any(), any(), anyInt())).thenReturn(List.of());

    mvc.perform(get("/api/darkpool/prints").header("X-Tenant-Id", "t"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(0));
    verify(repo).recentPrints(isNull(), isNull(), eq(BigDecimal.ZERO), eq(200));

    mvc.perform(get("/api/darkpool/prints").header("X-Tenant-Id", "t").param("limit", "5000"))
        .andExpect(status().isOk());
    verify(repo).recentPrints(isNull(), isNull(), eq(BigDecimal.ZERO), eq(1000));

    mvc.perform(get("/api/darkpool/prints").header("X-Tenant-Id", "t").param("limit", "0"))
        .andExpect(status().isOk());
    verify(repo).recentPrints(isNull(), isNull(), eq(BigDecimal.ZERO), eq(1));
  }

  @Test
  void prints_badSince_is400() throws Exception {
    mvc.perform(get("/api/darkpool/prints").header("X-Tenant-Id", "t").param("since", "yesterday"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(repo);
  }

  @Test
  void prints_badSymbol_is400() throws Exception {
    // The symbol is a SQL bind; keep it to the ticker charset so a stray value never reaches it.
    mvc.perform(
            get("/api/darkpool/prints")
                .header("X-Tenant-Id", "t")
                .param("symbol", "QQQ; DROP TABLE"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(repo);
  }

  @Test
  void summary_rendersPerSymbolShapeForTheRequestedSessionDate() throws Exception {
    when(repo.summary(LocalDate.of(2026, 10, 28)))
        .thenReturn(
            List.of(
                new SymbolSummary(
                    "QQQ",
                    4500,
                    13000,
                    0.3461538461538462,
                    2000,
                    2400,
                    0.45454545454545453,
                    0.43333333333333335,
                    3,
                    2000,
                    List.of(
                        new Level(new BigDecimal("500.02"), new BigDecimal("800030.00"), 2),
                        new Level(new BigDecimal("505.00"), new BigDecimal("252500.00"), 1))),
                new SymbolSummary("SPY", 0, 0, null, 0, 0, null, null, 0, 0, List.of())));

    mvc.perform(
            get("/api/darkpool/summary")
                .header("X-Tenant-Id", "prod_real")
                .param("date", "2026-10-28"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.date").value("2026-10-28"))
        .andExpect(jsonPath("$.count").value(2))
        .andExpect(jsonPath("$.symbols[0].symbol").value("QQQ"))
        .andExpect(jsonPath("$.symbols[0].dp_volume").value(4500))
        .andExpect(jsonPath("$.symbols[0].total_volume").value(13000))
        .andExpect(jsonPath("$.symbols[0].dp_share").value(0.3461538461538462))
        .andExpect(jsonPath("$.symbols[0].buy_volume").value(2000))
        .andExpect(jsonPath("$.symbols[0].sell_volume").value(2400))
        .andExpect(jsonPath("$.symbols[0].buy_ratio").value(0.45454545454545453))
        .andExpect(jsonPath("$.symbols[0].late_buy_ratio").value(0.43333333333333335))
        .andExpect(jsonPath("$.symbols[0].large_count").value(3))
        .andExpect(jsonPath("$.symbols[0].large_volume").value(2000))
        .andExpect(jsonPath("$.symbols[0].levels[0].price").value(500.02))
        .andExpect(jsonPath("$.symbols[0].levels[0].notional").value(800030.00))
        .andExpect(jsonPath("$.symbols[0].levels[0].prints").value(2))
        .andExpect(jsonPath("$.symbols[0].levels[1].price").value(505.00))
        // Ratios are null (not 0, not NaN) when there is nothing to divide by.
        .andExpect(jsonPath("$.symbols[1].symbol").value("SPY"))
        .andExpect(jsonPath("$.symbols[1].dp_share").value(nullValue()))
        .andExpect(jsonPath("$.symbols[1].buy_ratio").value(nullValue()))
        .andExpect(jsonPath("$.symbols[1].late_buy_ratio").value(nullValue()))
        .andExpect(jsonPath("$.symbols[1].levels").isEmpty());
  }

  @Test
  void summary_defaultsToTodayInEasternTime() throws Exception {
    when(repo.summary(any())).thenReturn(List.of());

    // Midnight-safe: the ET date may roll over between these two reads, so either is accepted.
    LocalDate before = LocalDate.now(ZoneId.of("America/New_York"));
    mvc.perform(get("/api/darkpool/summary").header("X-Tenant-Id", "t"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(0));
    LocalDate after = LocalDate.now(ZoneId.of("America/New_York"));

    ArgumentCaptor<LocalDate> used = ArgumentCaptor.forClass(LocalDate.class);
    verify(repo).summary(used.capture());
    assertThat(used.getValue()).isIn(before, after);
  }

  @Test
  void summary_badDate_is400() throws Exception {
    mvc.perform(get("/api/darkpool/summary").header("X-Tenant-Id", "t").param("date", "today"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(repo);
  }

  private static org.hamcrest.Matcher<Object> nullValue() {
    return org.hamcrest.Matchers.nullValue();
  }

  /** Dark: both reads are absent (404) while the feature flag is off. */
  @Nested
  @NestedTestConfiguration(EnclosingConfiguration.OVERRIDE)
  @WebMvcTest(DarkpoolController.class)
  @AutoConfigureMockMvc(addFilters = false)
  @Import(TenantContext.class)
  @TestPropertySource(properties = {"darkpool.enabled=false", "dashboard.writer.enabled=true"})
  class FeatureFlagOff {
    @Autowired private MockMvc mvc;
    @MockitoBean private DarkpoolRepository repo;

    @Test
    void bothReadsAre404() throws Exception {
      mvc.perform(get("/api/darkpool/prints").header("X-Tenant-Id", "prod_real"))
          .andExpect(status().isNotFound());
      mvc.perform(get("/api/darkpool/summary").header("X-Tenant-Id", "prod_real"))
          .andExpect(status().isNotFound());
    }
  }

  /** The writer flag off: no DSL for the repository, so the reads must be absent too. */
  @Nested
  @NestedTestConfiguration(EnclosingConfiguration.OVERRIDE)
  @WebMvcTest(DarkpoolController.class)
  @AutoConfigureMockMvc(addFilters = false)
  @Import(TenantContext.class)
  @TestPropertySource(properties = {"darkpool.enabled=true", "dashboard.writer.enabled=false"})
  class WriterFlagOff {
    @Autowired private MockMvc mvc;
    @MockitoBean private DarkpoolRepository repo;

    @Test
    void bothReadsAre404() throws Exception {
      mvc.perform(get("/api/darkpool/prints").header("X-Tenant-Id", "prod_real"))
          .andExpect(status().isNotFound());
      mvc.perform(get("/api/darkpool/summary").header("X-Tenant-Id", "prod_real"))
          .andExpect(status().isNotFound());
    }
  }
}
