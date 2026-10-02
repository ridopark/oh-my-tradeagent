package com.ohmytradeagent.marketdata.marks;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.marketdata.marks.DisplayMarksService.DisplayMark;
import com.ohmytradeagent.marketdata.marks.DisplayMarksService.Underlying;
import com.ohmytradeagent.marketdata.provider.Quote;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DisplayMarksController.class)
class DisplayMarksControllerTest {

  private static final String LIVE = "NVDA260516C00140000";
  private static final String WARM = "AMD260516C00150000";
  private static final String CAP = "TSLA260516C00250000";

  @Autowired private MockMvc mvc;
  @MockitoBean private DisplayMarksService service;

  @Test
  void marks_jsonShape_includesWarmingAndCapped() throws Exception {
    when(service.now()).thenReturn(Instant.parse("2026-10-01T14:30:01Z"));
    when(service.marks(List.of(LIVE, WARM, CAP)))
        .thenReturn(
            List.of(
                new DisplayMark(
                    LIVE,
                    new Quote(
                        LIVE,
                        new BigDecimal("2.90"),
                        new BigDecimal("2.95"),
                        new BigDecimal("3.00"),
                        OffsetDateTime.parse("2026-10-01T14:30:00Z")),
                    Instant.parse("2026-10-01T14:30:00.800Z"),
                    new Underlying(
                        "NVDA",
                        new BigDecimal("140.10"),
                        Instant.parse("2026-10-01T14:30:00.500Z")),
                    false,
                    false),
                new DisplayMark(WARM, null, null, new Underlying("AMD", null, null), true, false),
                new DisplayMark(CAP, null, null, new Underlying("TSLA", null, null), false, true)));

    // Whitespace (a padded OCC) is stripped and duplicates are dropped before the service sees it.
    mvc.perform(get("/md/marks").param("occ", LIVE, " AMD 260516C00150000", CAP, LIVE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.now").value("2026-10-01T14:30:01Z"))
        .andExpect(jsonPath("$.marks.length()").value(3))
        .andExpect(jsonPath("$.marks[0].occ").value(LIVE))
        .andExpect(jsonPath("$.marks[0].bid").value(2.90))
        .andExpect(jsonPath("$.marks[0].mid").value(2.95))
        .andExpect(jsonPath("$.marks[0].ask").value(3.00))
        .andExpect(jsonPath("$.marks[0].quote_at").value("2026-10-01T14:30:00Z"))
        .andExpect(jsonPath("$.marks[0].polled_at").value("2026-10-01T14:30:00.800Z"))
        .andExpect(jsonPath("$.marks[0].underlying.ticker").value("NVDA"))
        .andExpect(jsonPath("$.marks[0].underlying.price").value(140.10))
        .andExpect(jsonPath("$.marks[0].underlying.at").value("2026-10-01T14:30:00.500Z"))
        .andExpect(jsonPath("$.marks[0].warming").value(false))
        .andExpect(jsonPath("$.marks[0].capped").value(false))
        .andExpect(jsonPath("$.marks[1].occ").value(WARM))
        .andExpect(jsonPath("$.marks[1].bid").isEmpty())
        .andExpect(jsonPath("$.marks[1].quote_at").isEmpty())
        .andExpect(jsonPath("$.marks[1].underlying.price").isEmpty())
        .andExpect(jsonPath("$.marks[1].warming").value(true))
        .andExpect(jsonPath("$.marks[1].capped").value(false))
        .andExpect(jsonPath("$.marks[2].capped").value(true))
        .andExpect(jsonPath("$.marks[2].bid").isEmpty())
        .andExpect(jsonPath("$.marks[2].mid").isEmpty())
        .andExpect(jsonPath("$.marks[2].ask").isEmpty());

    verify(service).marks(List.of(LIVE, WARM, CAP));
  }

  @Test
  void marks_nullFieldsAreSerializedAsExplicitNulls() throws Exception {
    when(service.now()).thenReturn(Instant.parse("2026-10-01T14:30:01Z"));
    when(service.marks(List.of(WARM)))
        .thenReturn(
            List.of(
                new DisplayMark(WARM, null, null, new Underlying(null, null, null), true, false)));

    mvc.perform(get("/md/marks").param("occ", WARM))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.marks[0].bid").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.marks[0].quote_at").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(
            jsonPath("$.marks[0].underlying.ticker").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.marks[0].underlying.at").value(org.hamcrest.Matchers.nullValue()));
  }

  @Test
  void marks_invalidOccsAreDroppedAndNeverReachTheService() throws Exception {
    when(service.now()).thenReturn(Instant.parse("2026-10-01T14:30:01Z"));
    when(service.marks(List.of(LIVE)))
        .thenReturn(
            List.of(
                new DisplayMark(
                    LIVE, null, null, new Underlying("NVDA", null, null), true, false)));

    mvc.perform(
            get("/md/marks")
                .param(
                    "occ",
                    "junk",
                    LIVE,
                    "TOOLONGX260516C00140000",
                    "NVDA261316X00140000",
                    "NVDA260516C0014000",
                    "nvda260516c00140000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.marks.length()").value(1))
        .andExpect(jsonPath("$.marks[0].occ").value(LIVE));

    verify(service).marks(List.of(LIVE));
  }

  @Test
  void marks_atMostTwentyFiveOccsPerRequestReachTheService() throws Exception {
    when(service.now()).thenReturn(Instant.parse("2026-10-01T14:30:01Z"));
    String[] occs = new String[40];
    for (int i = 0; i < 40; i++) {
      occs[i] = "NVDA260516C00%03d000".formatted(100 + i);
    }
    mvc.perform(get("/md/marks").param("occ", occs)).andExpect(status().isOk());

    verify(service).marks(List.of(occs).subList(0, 25));
  }

  @Test
  void serializesPolledAt_rightAfterQuoteAt() throws Exception {
    when(service.now()).thenReturn(Instant.parse("2026-10-01T14:30:01Z"));
    when(service.marks(List.of(LIVE)))
        .thenReturn(
            List.of(
                new DisplayMark(
                    LIVE,
                    new Quote(
                        LIVE,
                        new BigDecimal("2.90"),
                        new BigDecimal("2.95"),
                        new BigDecimal("3.00"),
                        OffsetDateTime.parse("2026-10-01T14:29:00Z")),
                    Instant.parse("2026-10-01T14:30:00.500Z"),
                    new Underlying("NVDA", null, null),
                    false,
                    false)));

    String body =
        mvc.perform(get("/md/marks").param("occ", LIVE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.marks[0].quote_at").value("2026-10-01T14:29:00Z"))
            .andExpect(jsonPath("$.marks[0].polled_at").value("2026-10-01T14:30:00.500Z"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    org.assertj.core.api.Assertions.assertThat(body)
        .contains("\"quote_at\":\"2026-10-01T14:29:00Z\",\"polled_at\":");
  }

  @Test
  void polledAtNullWhenUnknown() throws Exception {
    when(service.now()).thenReturn(Instant.parse("2026-10-01T14:30:01Z"));
    when(service.marks(List.of(WARM)))
        .thenReturn(
            List.of(
                new DisplayMark(WARM, null, null, new Underlying("AMD", null, null), true, false)));

    String body =
        mvc.perform(get("/md/marks").param("occ", WARM))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    // An explicit null, not an absent key.
    org.assertj.core.api.Assertions.assertThat(body).contains("\"polled_at\":null");
  }
}
