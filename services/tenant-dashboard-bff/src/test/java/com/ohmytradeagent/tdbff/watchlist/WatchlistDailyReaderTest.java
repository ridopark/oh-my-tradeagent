package com.ohmytradeagent.tdbff.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.tdbff.watchlist.WatchlistDailyReader.Idea;
import java.math.BigDecimal;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

class WatchlistDailyReaderTest {

  private final WatchlistDailyReader reader =
      new WatchlistDailyReader(mock(DSLContext.class), new ObjectMapper());

  /**
   * The WIRE CONTRACT with the orchestrator's WatchlistDailyRepository.Idea, which serializes the
   * strike as the parsed STRING and the level as a number. The two records must be edited together;
   * WatchlistDailyRepositoryTest pins the producing side's mapping.
   */
  @Test
  void parse_readsTheExactShapeTheOrchestratorRecords() {
    assertThat(
            reader.parse(
                "[{\"ticker\":\"NVDA\",\"right\":\"C\",\"strike\":\"185\","
                    + "\"direction\":\"ABOVE\",\"level\":181.00}]"))
        .containsExactly(
            new Idea("NVDA", "C", new BigDecimal("185"), "ABOVE", new BigDecimal("181.00")));
  }

  @Test
  void parse_unrecognisedShape_throws_soTheRouteAnswersUnavailable() {
    assertThatThrownBy(() -> reader.parse("{\"not\":\"a list\"}"))
        .isInstanceOf(IllegalStateException.class);
  }
}
