package com.ohmytradeagent.marketdata.marks;

import com.ohmytradeagent.marketdata.marks.DisplayMarksService.DisplayMark;
import com.ohmytradeagent.marketdata.provider.Quote;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /md/marks?occ=..&occ=..} — cached display marks for the /live holdings table (PLAN
 * 2026-10-01 P1). Each request (re)registers display interest; the response only ever reads caches,
 * never calls a provider. Same no-auth, cluster-internal, display-only stance as {@code
 * MarketDataQuoteController}; explicit {@code @RequestParam} name for the same -parameters reason.
 *
 * <p>{@code {now, marks:[{occ, bid, mid, ask, quote_at, underlying:{ticker, price, at}, warming,
 * capped}]}} — nulls when unknown, ISO-8601 instants.
 */
@RestController
public class DisplayMarksController {

  private final DisplayMarksService service;

  public DisplayMarksController(DisplayMarksService service) {
    this.service = service;
  }

  @GetMapping("/md/marks")
  public Map<String, Object> marks(@RequestParam("occ") List<String> occs) {
    Set<String> compact = new LinkedHashSet<>();
    for (String occ : occs) {
      String c = occ.replaceAll("\\s", "");
      if (!c.isEmpty()) {
        compact.add(c);
      }
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (DisplayMark m : service.marks(List.copyOf(compact))) {
      Quote q = m.quote();
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("occ", m.occ());
      row.put("bid", q == null ? null : q.bid());
      row.put("mid", q == null ? null : q.mid());
      row.put("ask", q == null ? null : q.ask());
      row.put(
          "quote_at",
          q == null || q.retrievedAt() == null ? null : q.retrievedAt().toInstant().toString());
      Map<String, Object> underlying = new LinkedHashMap<>();
      underlying.put("ticker", m.underlying().ticker());
      underlying.put("price", m.underlying().price());
      underlying.put("at", m.underlying().at() == null ? null : m.underlying().at().toString());
      row.put("underlying", underlying);
      row.put("warming", m.warming());
      row.put("capped", m.capped());
      rows.add(row);
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("now", service.now().toString());
    out.put("marks", rows);
    return out;
  }
}
