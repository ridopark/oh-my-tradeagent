package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.darkpool.DarkpoolRepository;
import com.ohmytradeagent.tdbff.darkpool.DarkpoolRepository.Level;
import com.ohmytradeagent.tdbff.darkpool.DarkpoolRepository.Print;
import com.ohmytradeagent.tdbff.darkpool.DarkpoolRepository.SymbolSummary;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tenant-session reads behind the {@code /dark-pool} page: {@code GET
 * /api/darkpool/prints?symbol=&since=&min_notional=&limit=} (newest first) and {@code GET
 * /api/darkpool/summary?date=} (one ET session, per symbol).
 *
 * <p>The summary is REGULAR-TRADING-HOURS ONLY: every metric (volumes, shares, ratios, large-print
 * counts and price levels) is computed over bars whose bucket starts in [09:30, 16:00) ET of the
 * session date and prints in the same window; the late window is [14:00, 15:30) ET. The prints feed
 * is unfiltered — pre-/post-market prints are served, they just do not enter the summary.
 *
 * <p>TENANT-INDEPENDENT, like {@code OptionsChatController}: every session sees identical bytes,
 * because this is shared market data rather than per-tenant trading state. {@code X-Tenant-Id} is
 * still REQUIRED and still 401s when absent — it is the assertion that a signed-in session exists,
 * which keeps the auth shape uniform across the surface. Dark-gated via the two-name
 * {@code @ConditionalOnProperty}; see {@code application.yml}.
 */
@RestController
@RequestMapping("/api/darkpool")
@ConditionalOnProperty(
    name = {"darkpool.enabled", "dashboard.writer.enabled"},
    havingValue = "true")
public class DarkpoolController {

  private static final int DEFAULT_LIMIT = 200;
  private static final int MAX_LIMIT = 1000;
  private static final ZoneId ET = ZoneId.of("America/New_York");
  // The symbol is a SQL bind; keep it to the ticker charset (same spirit as TenantContext's id).
  private static final Pattern SYMBOL = Pattern.compile("[A-Za-z0-9.\\-]{1,16}");

  private final DarkpoolRepository repo;
  private final TenantContext ctx;

  public DarkpoolController(DarkpoolRepository repo, TenantContext ctx) {
    this.repo = repo;
    this.ctx = ctx;
  }

  @GetMapping("/prints")
  public ResponseEntity<Map<String, Object>> prints(
      HttpServletRequest req,
      @RequestParam(value = "symbol", required = false) String symbol,
      @RequestParam(value = "since", required = false) String since,
      @RequestParam(value = "min_notional", required = false) BigDecimal minNotional,
      @RequestParam(value = "limit", required = false, defaultValue = "" + DEFAULT_LIMIT)
          int limit) {
    // Authentication only — the value is intentionally unused (see the class javadoc).
    ctx.tenantId(req);

    if (symbol != null && !SYMBOL.matcher(symbol).matches()) {
      throw new IllegalArgumentException("invalid 'symbol'");
    }
    // A malformed `since` throws DateTimeParseException → GlobalExceptionHandler's 400.
    OffsetDateTime sinceTs = since == null || since.isBlank() ? null : OffsetDateTime.parse(since);
    BigDecimal threshold = minNotional == null ? BigDecimal.ZERO : minNotional;
    if (threshold.signum() < 0) {
      throw new IllegalArgumentException("invalid 'min_notional'");
    }

    List<Print> rows =
        repo.recentPrints(
            symbol == null ? null : symbol.toUpperCase(java.util.Locale.ROOT),
            sinceTs,
            threshold,
            Math.clamp(limit, 1, MAX_LIMIT));

    List<Map<String, Object>> items = new ArrayList<>(rows.size());
    for (Print p : rows) {
      Map<String, Object> j = new LinkedHashMap<>();
      // A string, like the Discord snowflakes: int64 ids are not safe as JavaScript numbers.
      j.put("trade_id", p.tradeId() == null ? null : Long.toString(p.tradeId()));
      j.put("symbol", p.symbol());
      j.put("ts", instant(p.ts()));
      j.put("price", p.price());
      j.put("size", p.size());
      j.put("notional", p.notional());
      j.put("exchange", p.exchange());
      j.put("conditions", p.conditions());
      j.put("tape", p.tape());
      j.put("bid", p.bid());
      j.put("ask", p.ask());
      j.put("quote_ts", instant(p.quoteTs()));
      j.put("side", p.side());
      items.add(j);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("count", items.size());
    body.put("items", items);
    return ResponseEntity.ok(body);
  }

  /**
   * Always {@code yyyy-MM-ddTHH:mm:ss[.fff]Z}: {@code OffsetDateTime.toString()} drops the seconds
   * when they are zero and keeps whatever offset the DB session handed back, so the page would see
   * two shapes for the same column.
   */
  private static String instant(OffsetDateTime t) {
    return t == null ? null : DateTimeFormatter.ISO_INSTANT.format(t);
  }

  @GetMapping("/summary")
  public ResponseEntity<Map<String, Object>> summary(
      HttpServletRequest req, @RequestParam(value = "date", required = false) String date) {
    ctx.tenantId(req);

    LocalDate session;
    if (date == null || date.isBlank()) {
      session = LocalDate.now(ET);
    } else {
      try {
        session = LocalDate.parse(date);
      } catch (DateTimeParseException e) {
        // Not left to the DateTimeParseException handler: its fixed message names 'since'.
        throw new IllegalArgumentException("invalid 'date'; expected YYYY-MM-DD");
      }
    }

    List<SymbolSummary> rows = repo.summary(session);
    List<Map<String, Object>> symbols = new ArrayList<>(rows.size());
    for (SymbolSummary s : rows) {
      Map<String, Object> j = new LinkedHashMap<>();
      j.put("symbol", s.symbol());
      j.put("dp_volume", s.dpVolume());
      j.put("total_volume", s.totalVolume());
      j.put("dp_share", s.dpShare());
      j.put("buy_volume", s.buyVolume());
      j.put("sell_volume", s.sellVolume());
      j.put("buy_ratio", s.buyRatio());
      j.put("late_buy_ratio", s.lateBuyRatio());
      j.put("large_count", s.largeCount());
      j.put("large_volume", s.largeVolume());
      List<Map<String, Object>> levels = new ArrayList<>(s.levels().size());
      for (Level l : s.levels()) {
        Map<String, Object> lj = new LinkedHashMap<>();
        lj.put("price", l.price());
        lj.put("notional", l.notional());
        lj.put("prints", l.prints());
        levels.add(lj);
      }
      j.put("levels", levels);
      symbols.add(j);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("date", session.toString());
    body.put("count", symbols.size());
    body.put("symbols", symbols);
    return ResponseEntity.ok(body);
  }
}
