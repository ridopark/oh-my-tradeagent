package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.watchlist.WatchlistDailyReader;
import com.ohmytradeagent.tdbff.watchlist.WatchlistDailyReader.Idea;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /internal/flow/watchlist?date=} — returns today's watchlist tickers so the
 * flow-collector can union them into its quote universe without a tenant credential. The caller is
 * the flow-collector pod; it holds the ingest token, not the shared token.
 *
 * <p>Auth is the same ingest token that gates {@code /internal/flow/ingest} (via the {@code
 * /internal/flow/} prefix in {@code ServiceTokenFilter}). Dark-gated on both {@code flow.enabled}
 * and {@code dashboard.writer.enabled}.
 *
 * <p>A missing or null watchlist (nothing posted yet that day) returns an empty ticker list rather
 * than 404, so the collector can fall back gracefully to its static universe.
 */
@RestController
@RequestMapping("/internal/flow")
@ConditionalOnProperty(
    name = {"flow.enabled", "dashboard.writer.enabled"},
    havingValue = "true")
public class FlowInternalController {

  private static final ZoneId ET = ZoneId.of("America/New_York");

  private final WatchlistDailyReader watchlistReader;

  public FlowInternalController(WatchlistDailyReader watchlistReader) {
    this.watchlistReader = watchlistReader;
  }

  @GetMapping("/watchlist")
  public ResponseEntity<Map<String, Object>> watchlist(
      @RequestParam(value = "date", required = false) String date) {
    LocalDate etDate;
    if (date == null || date.isBlank()) {
      etDate = LocalDate.now(ET);
    } else {
      try {
        etDate = LocalDate.parse(date);
      } catch (DateTimeParseException e) {
        throw new IllegalArgumentException("invalid 'date'; expected YYYY-MM-DD");
      }
    }

    List<Idea> ideas = watchlistReader.ideasFor(etDate);
    // Distinct tickers, sorted for a stable response.
    TreeSet<String> tickerSet = new TreeSet<>();
    if (ideas != null) {
      for (Idea idea : ideas) {
        if (idea.ticker() != null) {
          tickerSet.add(idea.ticker());
        }
      }
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("date", etDate.toString());
    body.put("tickers", new ArrayList<>(tickerSet));
    return ResponseEntity.ok(body);
  }
}
