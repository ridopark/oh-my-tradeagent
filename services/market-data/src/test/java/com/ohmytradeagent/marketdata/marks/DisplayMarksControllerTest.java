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
                    new Underlying(
                        "NVDA",
                        new BigDecimal("140.10"),
                        Instant.parse("2026-10-01T14:30:00.500Z")),
                    false,
                    false),
                new DisplayMark(WARM, null, new Underlying("AMD", null, null), true, false),
                new DisplayMark(CAP, null, new Underlying("TSLA", null, null), false, true)));

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
            List.of(new DisplayMark(WARM, null, new Underlying(null, null, null), true, false)));

    mvc.perform(get("/md/marks").param("occ", WARM))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.marks[0].bid").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.marks[0].quote_at").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(
            jsonPath("$.marks[0].underlying.ticker").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.marks[0].underlying.at").value(org.hamcrest.Matchers.nullValue()));
  }
}
