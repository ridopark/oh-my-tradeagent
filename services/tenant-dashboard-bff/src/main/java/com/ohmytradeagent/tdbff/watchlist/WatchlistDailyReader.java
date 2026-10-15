package com.ohmytradeagent.tdbff.watchlist;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Reads the shared daily watchlist the orchestrator records in {@code watchlist_daily} (its V10)
 * when the author's post arrives. One row per ET date, not per tenant: every account's /live chain
 * picker shows the same list. Throws on a database error so the caller can answer "unavailable"
 * instead of "no watchlist today".
 */
@Component
public class WatchlistDailyReader {

  /** One idea as the orchestrator records it: call = ABOVE/C, put = BELOW/P. */
  public record Idea(
      String ticker, String right, BigDecimal strike, String direction, BigDecimal level) {}

  private static final TypeReference<List<Idea>> IDEAS = new TypeReference<>() {};

  private final DSLContext orchestratorDsl;
  private final ObjectMapper objectMapper;

  public WatchlistDailyReader(
      @Qualifier("orchestratorDsl") DSLContext orchestratorDsl, ObjectMapper objectMapper) {
    this.orchestratorDsl = orchestratorDsl;
    this.objectMapper = objectMapper;
  }

  /** The ideas posted for {@code etDate}, or null when nothing was posted that day. */
  public List<Idea> ideasFor(LocalDate etDate) {
    JSONB ideas =
        orchestratorDsl
            .select(DSL.field("ideas", JSONB.class))
            .from(DSL.table("watchlist_daily"))
            .where(DSL.field("et_date", LocalDate.class).eq(etDate))
            .fetchOne(0, JSONB.class);
    return ideas == null ? null : parse(ideas.data());
  }

  List<Idea> parse(String json) {
    try {
      return objectMapper.readValue(json, IDEAS);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("watchlist_daily.ideas is not the recorded shape", e);
    }
  }
}
