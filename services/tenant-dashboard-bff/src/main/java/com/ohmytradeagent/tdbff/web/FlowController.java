package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.flow.FlowIngestBatch;
import com.ohmytradeagent.tdbff.flow.FlowRepository;
import com.ohmytradeagent.tdbff.flow.FlowRepository.Order;
import com.ohmytradeagent.tdbff.flow.FlowRepository.OrderPage;
import com.ohmytradeagent.tdbff.flow.FlowRepository.OrderQuery;
import com.ohmytradeagent.tdbff.flow.FlowRepository.TickerDay;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tenant-session reads behind the {@code /flow} page: {@code GET
 * /api/flow/orders?date=&ticker=&min_premium=&labels=&right=&side=&dte_min=&dte_max=&before=&before_id=&limit=}
 * (newest first) and {@code GET /api/flow/summary?date=&ticker=} (per-ticker daily totals).
 *
 * <p>TENANT-INDEPENDENT, like {@code DarkpoolController}: every session sees identical bytes.
 * {@code X-Tenant-Id} is still REQUIRED — it asserts a signed-in session exists. Dark-gated via the
 * two-name {@code @ConditionalOnProperty}; see {@code application.yml}.
 */
@RestController
@RequestMapping("/api/flow")
@ConditionalOnProperty(
    name = {"flow.enabled", "dashboard.writer.enabled"},
    havingValue = "true")
public class FlowController {

  private static final int DEFAULT_LIMIT = 200;
  private static final int MAX_LIMIT = 500;
  private static final ZoneId ET = ZoneId.of("America/New_York");
  // Valid label values for the labels= filter.
  private static final Set<String> VALID_LABELS = Set.of("sweep", "block", "multi_leg", "unusual");
  // The ticker is a SQL bind; keep it to the ticker charset.
  private static final Pattern TICKER = Pattern.compile("[A-Za-z0-9.\\-]{1,16}");
  // Reuse FlowIngestBatch's sets so the accepted values are defined once.
  private static final Set<String> VALID_RIGHTS = FlowIngestBatch.RIGHTS;
  private static final Set<String> VALID_SIDES = FlowIngestBatch.SIDES;

  private final FlowRepository repo;
  private final TenantContext ctx;

  public FlowController(FlowRepository repo, TenantContext ctx) {
    this.repo = repo;
    this.ctx = ctx;
  }

  @GetMapping("/orders")
  public ResponseEntity<Map<String, Object>> orders(
      HttpServletRequest req,
      @RequestParam(value = "date", required = false) String date,
      @RequestParam(value = "ticker", required = false) String ticker,
      @RequestParam(value = "min_premium", required = false) BigDecimal minPremium,
      @RequestParam(value = "labels", required = false) String labels,
      @RequestParam(value = "right", required = false) String right,
      @RequestParam(value = "side", required = false) String side,
      @RequestParam(value = "dte_min", required = false) Integer dteMin,
      @RequestParam(value = "dte_max", required = false) Integer dteMax,
      @RequestParam(value = "before", required = false) String before,
      @RequestParam(value = "before_id", required = false) Long beforeId,
      @RequestParam(value = "limit", required = false, defaultValue = "" + DEFAULT_LIMIT)
          int limit) {
    ctx.tenantId(req);

    // Default to today in ET, like /summary does (amended at review 2026-10-31).
    LocalDate sessionDate = parseDate(date);
    if (sessionDate == null) {
      sessionDate = LocalDate.now(ET);
    }

    if (ticker != null && !TICKER.matcher(ticker).matches()) {
      throw new IllegalArgumentException("invalid 'ticker'");
    }
    if (right != null && !VALID_RIGHTS.contains(right)) {
      throw new IllegalArgumentException("invalid 'right'; expected C or P");
    }
    if (side != null && !VALID_SIDES.contains(side)) {
      throw new IllegalArgumentException("invalid 'side'");
    }
    if (minPremium != null && minPremium.signum() < 0) {
      throw new IllegalArgumentException("invalid 'min_premium'");
    }

    Set<String> labelSet = parseLabels(labels);
    // before and before_id must come together: one without the other is a malformed cursor.
    // A blank before (?before=) counts as absent, or it would silently drop the cursor.
    if (before != null && before.isBlank()) {
      before = null;
    }
    if ((before == null) != (beforeId == null)) {
      throw new IllegalArgumentException(
          "cursor params 'before' and 'before_id' must be supplied together");
    }
    OffsetDateTime beforeTs = before == null ? null : OffsetDateTime.parse(before);

    OrderQuery query =
        new OrderQuery(
            sessionDate,
            ticker == null ? null : ticker.toUpperCase(java.util.Locale.ROOT),
            minPremium,
            labelSet,
            right,
            side,
            dteMin,
            dteMax,
            beforeTs,
            beforeId,
            Math.clamp(limit, 1, MAX_LIMIT));

    OrderPage page = repo.orders(query);
    List<Order> rows = page.items();

    List<Map<String, Object>> items = new ArrayList<>(rows.size());
    for (Order o : rows) {
      Map<String, Object> j = new LinkedHashMap<>();
      j.put("order_id", o.orderId());
      j.put("ticker", o.ticker());
      j.put("contract", o.contract());
      j.put("expiry", o.expiry() == null ? null : o.expiry().toString());
      j.put("strike", o.strike());
      j.put("right", o.right());
      j.put("first_ts", instant(o.firstTs()));
      j.put("last_ts", instant(o.lastTs()));
      j.put("trade_count", o.tradeCount());
      j.put("exchange_count", o.exchangeCount());
      j.put("size", o.size());
      j.put("premium", o.premium());
      j.put("vwap", o.vwap());
      j.put("side", o.side());
      j.put("side_source", o.sideSource());
      j.put("bid", o.bid());
      j.put("ask", o.ask());
      j.put("quote_ts", instant(o.quoteTs()));
      j.put("sweep", o.sweep());
      j.put("multi_leg", o.multiLeg());
      j.put("auction", o.auction());
      j.put("cross", o.cross());
      j.put("block", o.block());
      j.put("unusual", o.unusual());
      j.put("day_volume", o.dayVolume());
      j.put("open_interest", o.openInterest());
      j.put("underlying_price", o.underlyingPrice());
      j.put("moneyness_pct", o.moneynessPct());
      j.put("dte", o.dte());
      j.put("conditions", o.conditions());
      j.put("block_premium_threshold", o.blockPremiumThreshold());
      j.put("block_size_threshold", o.blockSizeThreshold());
      j.put("order_window_ms", o.orderWindowMs());
      j.put("quote_max_age_ms", o.quoteMaxAgeMs());
      items.add(j);
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("date", sessionDate.toString());
    body.put("count", items.size());
    body.put("items", items);
    // Emit the cursor only when the page is full (rows.size() == limit), meaning a further page
    // may exist. A short page is the last page and needs no cursor (amended at review 2026-10-31).
    if (rows.size() == query.limit() && page.lastFirstTs() != null) {
      body.put("next_before", instant(page.lastFirstTs()));
      body.put("next_before_id", page.lastId());
    }
    return ResponseEntity.ok(body);
  }

  @GetMapping("/summary")
  public ResponseEntity<Map<String, Object>> summary(
      HttpServletRequest req,
      @RequestParam(value = "date", required = false) String date,
      @RequestParam(value = "ticker", required = false) String ticker) {
    ctx.tenantId(req);

    LocalDate sessionDate = parseDate(date);
    if (sessionDate == null) {
      sessionDate = LocalDate.now(ET);
    }

    if (ticker != null && !TICKER.matcher(ticker).matches()) {
      throw new IllegalArgumentException("invalid 'ticker'");
    }

    String tickerUpper = ticker == null ? null : ticker.toUpperCase(java.util.Locale.ROOT);
    List<TickerDay> rows = repo.summary(sessionDate, tickerUpper);

    List<Map<String, Object>> tickers = new ArrayList<>(rows.size());
    for (TickerDay t : rows) {
      Map<String, Object> j = new LinkedHashMap<>();
      j.put("ticker", t.ticker());
      j.put("session_date", t.sessionDate() == null ? null : t.sessionDate().toString());
      j.put("call_premium", t.callPremium());
      j.put("put_premium", t.putPremium());
      j.put("bought_premium", t.boughtPremium());
      j.put("sold_premium", t.soldPremium());
      j.put("mid_premium", t.midPremium());
      j.put("unknown_premium", t.unknownPremium());
      j.put("trade_count", t.tradeCount());
      j.put("order_count", t.orderCount());
      j.put("quoted_premium_share", t.quotedPremiumShare());
      tickers.add(j);
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("date", sessionDate.toString());
    body.put("count", tickers.size());
    body.put("tickers", tickers);
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

  private static LocalDate parseDate(String date) {
    if (date == null || date.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(date);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("invalid 'date'; expected YYYY-MM-DD");
    }
  }

  /** Parse comma-separated label values; silently ignore unknown labels. */
  private static Set<String> parseLabels(String labels) {
    if (labels == null || labels.isBlank()) {
      return null;
    }
    return Arrays.stream(labels.split(","))
        .map(String::trim)
        .filter(VALID_LABELS::contains)
        .collect(Collectors.toSet());
  }
}
