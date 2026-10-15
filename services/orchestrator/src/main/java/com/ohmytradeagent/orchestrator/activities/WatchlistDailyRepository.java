package com.ohmytradeagent.orchestrator.activities;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/**
 * Writes today's watchlist to {@code watchlist_daily} (V10) — the shared, tenant-independent copy
 * the /live chain picker reads through the BFF. Display-only; nothing in the trading path reads it.
 *
 * <p>Write-once per ET date: {@code ON CONFLICT (et_date) DO NOTHING}, so the same post mirrored to
 * several tenants (and activity retries) land as one row. Methods may throw; the caller wraps the
 * call so a database problem can never reach the digest post or the trigger session.
 */
@Component
public class WatchlistDailyRepository {

  /** One idea, mapped exactly as {@link WatchlistTriggerRowMapper} maps a leg for trading. */
  public record Idea(
      String ticker, String right, String strike, String direction, BigDecimal level) {}

  private final DSLContext dsl;
  private final ObjectMapper objectMapper;

  public WatchlistDailyRepository(DSLContext dsl, ObjectMapper objectMapper) {
    this.dsl = dsl;
    this.objectMapper = objectMapper;
  }

  public void record(LocalDate etDate, String sourceMessageId, String author, List<Idea> ideas) {
    String json;
    try {
      json = objectMapper.writeValueAsString(ideas);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("could not serialize watchlist ideas", e);
    }
    dsl.execute(
        "INSERT INTO watchlist_daily (et_date, source_message_id, author, ideas) "
            // Explicit casts: jOOQ binds LocalDate and String as varchar parameters, and Postgres
            // refuses varchar->date / varchar->jsonb in an INSERT without one (the trade_context
            // entry_at lesson, TradeContextRepository).
            + "VALUES (?::date, ?, ?, ?::jsonb) "
            + "ON CONFLICT (et_date) DO NOTHING",
        etDate.toString(),
        sourceMessageId,
        author,
        json);
  }

  /**
   * The ideas in a clean parse, mapped as the trigger session maps them (call=ABOVE/C,
   * put=BELOW/P).
   */
  static List<Idea> ideasOf(List<WatchlistParser.TickerWatch> rows) {
    return rows.stream()
        .flatMap(
            w ->
                java.util.stream.Stream.of(
                        w.call() == null
                            ? null
                            : new Idea(
                                w.ticker(), "C", w.call().strike(), "ABOVE", w.call().trigger()),
                        w.put() == null
                            ? null
                            : new Idea(
                                w.ticker(), "P", w.put().strike(), "BELOW", w.put().trigger()))
                    .filter(java.util.Objects::nonNull))
        .toList();
  }
}
