package com.ohmytradeagent.tdbff.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.darkpool.DarkpoolRepository;
import com.ohmytradeagent.tdbff.darkpool.DarkpoolRepository.Bar;
import com.ohmytradeagent.tdbff.darkpool.DarkpoolRepository.IngestResult;
import com.ohmytradeagent.tdbff.darkpool.DarkpoolRepository.Print;
import com.ohmytradeagent.tdbff.security.ServiceTokenFilter;
import java.math.BigDecimal;
import java.net.URI;
import java.time.OffsetDateTime;
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
 * Web-layer contract for {@code POST /internal/darkpool/ingest}. Runs the REAL {@link
 * ServiceTokenFilter}: the route accepts only the ingest token, and the traversal guard must hold
 * for the second prefix exactly as it does for options-chat.
 */
@WebMvcTest(DarkpoolIngestController.class)
@Import(ServiceTokenFilter.class)
@TestPropertySource(
    properties = {
      "darkpool.enabled=true",
      "dashboard.writer.enabled=true",
      "bff.service-token=test-shared-token",
      "options-chat.ingest-token=test-ingest-token"
    })
class DarkpoolIngestControllerWebMvcTest {

  private static final String INGEST = "Bearer test-ingest-token";
  private static final String SHARED = "Bearer test-shared-token";

  /**
   * snake_case on the wire — identical to the V15 columns and the GET responses (plan contract).
   */
  private static final String ONE_PRINT_ONE_BAR =
      """
      {"prints":[
        {"trade_id":52983525029461,"symbol":"QQQ","ts":"2026-10-29T18:01:02.123456Z",
         "price":612.3400,"size":2500,"notional":1530850.00,"exchange":"D","conditions":"@",
         "tape":"B","bid":612.33,"ask":612.35,"quote_ts":"2026-10-29T18:01:02.100Z","side":"buy"}
      ],
      "bars":[
        {"symbol":"QQQ","bucket":"2026-10-29T18:00:00Z","dp_volume":40000,"dp_trades":120,
         "total_volume":250000,"total_trades":2100,"buy_volume":25000,"sell_volume":12000,
         "large_count":3,"large_volume":9000,"dp_vwap":612.3012}
      ]}""";

  @Autowired private MockMvc mvc;
  @MockitoBean private DarkpoolRepository repo;

  @Test
  void noToken_is401_andNothingIsWritten() throws Exception {
    mvc.perform(
            post("/internal/darkpool/ingest")
                .contentType("application/json")
                .content(ONE_PRINT_ONE_BAR))
        .andExpect(status().isUnauthorized());
    verify(repo, never()).ingest(anyList(), anyList());
  }

  @Test
  void sharedToken_is401() throws Exception {
    mvc.perform(
            post("/internal/darkpool/ingest")
                .header("Authorization", SHARED)
                .contentType("application/json")
                .content(ONE_PRINT_ONE_BAR))
        .andExpect(status().isUnauthorized());
    verify(repo, never()).ingest(anyList(), anyList());
  }

  @Test
  void traversalUnderTheDarkpoolPrefix_isRejected() throws Exception {
    // Built from a java.net.URI so the RAW path reaches the filter: the String overload
    // re-encodes `%` to `%25`, which is not a traversal and would make this test pass vacuously.
    mvc.perform(
            post(URI.create("/internal/darkpool/%2e%2e/api/positions"))
                .header("Authorization", INGEST)
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void validBatch_storesAndReportsCounts() throws Exception {
    when(repo.ingest(anyList(), anyList())).thenReturn(new IngestResult(1, 1));

    mvc.perform(
            post("/internal/darkpool/ingest")
                .header("Authorization", INGEST)
                .contentType("application/json")
                .content(ONE_PRINT_ONE_BAR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stored_prints").value(1))
        .andExpect(jsonPath("$.stored_bars").value(1));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Print>> prints = ArgumentCaptor.forClass(List.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Bar>> bars = ArgumentCaptor.forClass(List.class);
    verify(repo).ingest(prints.capture(), bars.capture());

    assertThat(prints.getValue()).hasSize(1);
    Print p = prints.getValue().get(0);
    assertThat(p.tradeId()).isEqualTo(52983525029461L);
    assertThat(p.symbol()).isEqualTo("QQQ");
    assertThat(p.ts()).isEqualTo(OffsetDateTime.parse("2026-10-29T18:01:02.123456Z"));
    assertThat(p.price()).isEqualByComparingTo(new BigDecimal("612.34"));
    assertThat(p.size()).isEqualTo(2500);
    assertThat(p.side()).isEqualTo("buy");

    assertThat(bars.getValue()).hasSize(1);
    Bar b = bars.getValue().get(0);
    assertThat(b.bucket()).isEqualTo(OffsetDateTime.parse("2026-10-29T18:00:00Z"));
    assertThat(b.dpVolume()).isEqualTo(40000L);
    assertThat(b.dpVwap()).isEqualByComparingTo(new BigDecimal("612.3012"));
  }

  @Test
  void aCamelCaseBody_is400_soTheWireNamesArePinned() throws Exception {
    // The contract is snake_case. A camelCase key is an UNKNOWN property, so the required field
    // is absent and the validator rejects the row — the names cannot drift silently.
    mvc.perform(
            post("/internal/darkpool/ingest")
                .header("Authorization", INGEST)
                .contentType("application/json")
                .content(
                    ONE_PRINT_ONE_BAR
                        .replace("\"trade_id\"", "\"tradeId\"")
                        .replace("\"quote_ts\"", "\"quoteTs\"")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("invalid or missing print.trade_id"));
    mvc.perform(
            post("/internal/darkpool/ingest")
                .header("Authorization", INGEST)
                .contentType("application/json")
                .content(ONE_PRINT_ONE_BAR.replace("\"dp_volume\"", "\"dpVolume\"")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("invalid or missing bar.dp_volume"));
    verify(repo, never()).ingest(anyList(), anyList());
  }

  @Test
  void anUnknownSide_is400_andNothingIsWritten() throws Exception {
    mvc.perform(
            post("/internal/darkpool/ingest")
                .header("Authorization", INGEST)
                .contentType("application/json")
                .content(ONE_PRINT_ONE_BAR.replace("\"side\":\"buy\"", "\"side\":\"short\"")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("bad_request"));
    verify(repo, never()).ingest(anyList(), anyList());
  }

  @Test
  void aMissingSide_is400() throws Exception {
    mvc.perform(
            post("/internal/darkpool/ingest")
                .header("Authorization", INGEST)
                .contentType("application/json")
                .content(ONE_PRINT_ONE_BAR.replace(",\"side\":\"buy\"", "")))
        .andExpect(status().isBadRequest());
    verify(repo, never()).ingest(anyList(), anyList());
  }

  @Test
  void aPrintWithoutItsIdentity_is400() throws Exception {
    // The unique key is (symbol, ts, trade_id); a print missing any of them cannot dedupe.
    mvc.perform(
            post("/internal/darkpool/ingest")
                .header("Authorization", INGEST)
                .contentType("application/json")
                .content(ONE_PRINT_ONE_BAR.replace("\"trade_id\":52983525029461,", "")))
        .andExpect(status().isBadRequest());
    verify(repo, never()).ingest(anyList(), anyList());
  }

  @Test
  void aNonPositivePrice_is400() throws Exception {
    // The summary's price-level bucketing takes ln(price); a zero would 500 every read.
    mvc.perform(
            post("/internal/darkpool/ingest")
                .header("Authorization", INGEST)
                .contentType("application/json")
                .content(ONE_PRINT_ONE_BAR.replace("\"price\":612.3400", "\"price\":0")))
        .andExpect(status().isBadRequest());
    verify(repo, never()).ingest(anyList(), anyList());
  }

  @Test
  void moreThan5000Prints_is400() throws Exception {
    String print =
        "{\"trade_id\":%d,\"symbol\":\"QQQ\",\"ts\":\"2026-10-29T18:01:02Z\",\"price\":612.34,"
            + "\"size\":100,\"notional\":61234.00,\"side\":\"unknown\"}";
    StringBuilder body = new StringBuilder("{\"prints\":[");
    for (int i = 0; i < 5001; i++) {
      if (i > 0) {
        body.append(',');
      }
      body.append(String.format(print, i));
    }
    body.append("],\"bars\":[]}");

    mvc.perform(
            post("/internal/darkpool/ingest")
                .header("Authorization", INGEST)
                .contentType("application/json")
                .content(body.toString()))
        .andExpect(status().isBadRequest());
    verify(repo, never()).ingest(anyList(), anyList());
  }

  @Test
  void malformedJson_is400() throws Exception {
    mvc.perform(
            post("/internal/darkpool/ingest")
                .header("Authorization", INGEST)
                .contentType("application/json")
                .content("{\"prints\":[{\"trade_id\":\"not-a-number\"}"))
        .andExpect(status().isBadRequest());
    verify(repo, never()).ingest(anyList(), anyList());
  }

  @Test
  void anEmptyBatchIsAccepted() throws Exception {
    when(repo.ingest(anyList(), anyList())).thenReturn(new IngestResult(0, 0));

    mvc.perform(
            post("/internal/darkpool/ingest")
                .header("Authorization", INGEST)
                .contentType("application/json")
                .content("{\"prints\":[],\"bars\":[]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stored_prints").value(0))
        .andExpect(jsonPath("$.stored_bars").value(0));
  }

  /** Dark: the feature flag off makes the route absent (404), filters aside. */
  @Nested
  @NestedTestConfiguration(EnclosingConfiguration.OVERRIDE)
  @WebMvcTest(DarkpoolIngestController.class)
  @AutoConfigureMockMvc(addFilters = false)
  @TestPropertySource(properties = {"darkpool.enabled=false", "dashboard.writer.enabled=true"})
  class FeatureFlagOff {
    @Autowired private MockMvc mvc;
    @MockitoBean private DarkpoolRepository repo;

    @Test
    void ingestRouteIs404() throws Exception {
      mvc.perform(
              post("/internal/darkpool/ingest")
                  .contentType("application/json")
                  .content("{\"prints\":[],\"bars\":[]}"))
          .andExpect(status().isNotFound());
    }
  }

  /** The writer flag off: the DSL the repository needs is absent, so the route must be too. */
  @Nested
  @NestedTestConfiguration(EnclosingConfiguration.OVERRIDE)
  @WebMvcTest(DarkpoolIngestController.class)
  @AutoConfigureMockMvc(addFilters = false)
  @TestPropertySource(properties = {"darkpool.enabled=true", "dashboard.writer.enabled=false"})
  class WriterFlagOff {
    @Autowired private MockMvc mvc;
    @MockitoBean private DarkpoolRepository repo;

    @Test
    void ingestRouteIs404() throws Exception {
      mvc.perform(
              post("/internal/darkpool/ingest")
                  .contentType("application/json")
                  .content("{\"prints\":[],\"bars\":[]}"))
          .andExpect(status().isNotFound());
    }
  }
}
