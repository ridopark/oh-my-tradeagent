package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.entries.OccParser;
import com.ohmytradeagent.tdbff.entries.OccParser.InvalidOccException;
import com.ohmytradeagent.tdbff.entries.OccParser.ParsedOcc;
import com.ohmytradeagent.tdbff.proximity.MarketDataQuoteClient;
import com.ohmytradeagent.tdbff.proximity.MarketDataQuoteClient.ChainExpirations;
import com.ohmytradeagent.tdbff.proximity.MarketDataQuoteClient.ChainRow;
import com.ohmytradeagent.tdbff.proximity.MarketDataQuoteClient.OptionQuote;
import com.ohmytradeagent.tdbff.proximity.MarketResearchClient;
import com.ohmytradeagent.tdbff.proximity.MarketResearchClient.RateLimitedException;
import com.ohmytradeagent.tdbff.proximity.MarketResearchClient.UnavailableException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only market research for MCP clients: {@code GET /api/market/*}. Seven routes proxy the
 * market-data worker's {@code /md/research/*}; three (option quote, chain expirations, chain) are
 * the same reads {@link ManualEntryController} offers the /live chain picker.
 *
 * <p>Deliberately separate from {@link ManualEntryController}: those routes sit behind the
 * manual-entry WRITE flag and its tenant allowlist because they exist to feed an order. A market
 * read prices nothing, so NOTHING here consults that flag — and nothing here is tenant data, so no
 * tenant is resolved either. Authentication is still the service's {@code ServiceTokenFilter}.
 *
 * <p>Every input is a query parameter (the MCP allowlist matches exact raw paths) and is validated
 * here, so market-data only ever sees values this class has checked.
 */
@RestController
@RequestMapping("/api/market")
public class MarketController {

  private static final Pattern TICKER = Pattern.compile("^[A-Z]{1,6}$");
  private static final Set<String> TIMEFRAMES =
      Set.of("1Min", "5Min", "15Min", "30Min", "1Hour", "1Day", "1Week", "1Month");
  private static final int MAX_NEWS_SYMBOLS = 20;
  private static final int MAX_BARS = 1000;
  private static final int MAX_NEWS = 50;
  private static final int MAX_MOVERS = 50;

  private final MarketResearchClient research;
  private final MarketDataQuoteClient quotes;

  public MarketController(MarketResearchClient research, MarketDataQuoteClient quotes) {
    this.research = research;
    this.quotes = quotes;
  }

  @GetMapping("/snapshot")
  public Map<String, Object> snapshot(
      @RequestParam(value = "ticker", required = false) String ticker) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("ticker", ticker(ticker, "ticker"));
    return research.get("snapshot", params);
  }

  @GetMapping("/bars")
  public Map<String, Object> bars(
      @RequestParam(value = "ticker", required = false) String ticker,
      @RequestParam(value = "timeframe", required = false) String timeframe,
      @RequestParam(value = "start", required = false) String start,
      @RequestParam(value = "end", required = false) String end,
      @RequestParam(value = "limit", required = false) String limit) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("ticker", ticker(ticker, "ticker"));
    if (timeframe == null || !TIMEFRAMES.contains(timeframe)) {
      throw new InvalidRequestException(
          "timeframe must be one of 1Min, 5Min, 15Min, 30Min, 1Hour, 1Day, 1Week, 1Month");
    }
    params.put("timeframe", timeframe);
    params.put("start", required(dateOrInstant(start, "start"), "start"));
    putIfPresent(params, "end", dateOrInstant(end, "end"));
    putIfPresent(params, "limit", limit(limit, "limit", MAX_BARS));
    return research.get("bars", params);
  }

  @GetMapping("/news")
  public Map<String, Object> news(
      @RequestParam(value = "symbols", required = false) String symbols,
      @RequestParam(value = "start", required = false) String start,
      @RequestParam(value = "limit", required = false) String limit) {
    Map<String, String> params = new LinkedHashMap<>();
    putIfPresent(params, "symbols", symbols(symbols));
    putIfPresent(params, "start", dateOrInstant(start, "start"));
    putIfPresent(params, "limit", limit(limit, "limit", MAX_NEWS));
    return research.get("news", params);
  }

  @GetMapping("/movers")
  public Map<String, Object> movers(@RequestParam(value = "top", required = false) String top) {
    Map<String, String> params = new LinkedHashMap<>();
    putIfPresent(params, "top", limit(top, "top", MAX_MOVERS));
    return research.get("movers", params);
  }

  @GetMapping("/clock")
  public Map<String, Object> clock() {
    return research.get("clock", Map.of());
  }

  @GetMapping("/corporate-actions")
  public Map<String, Object> corporateActions(
      @RequestParam(value = "symbol", required = false) String symbol,
      @RequestParam(value = "start", required = false) String start,
      @RequestParam(value = "end", required = false) String end) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("symbol", ticker(symbol, "symbol"));
    params.put("start", required(date(start, "start"), "start"));
    putIfPresent(params, "end", date(end, "end"));
    return research.get("corporate-actions", params);
  }

  @GetMapping("/open-interest")
  public Map<String, Object> openInterest(
      @RequestParam(value = "underlying", required = false) String underlying,
      @RequestParam(value = "expiration", required = false) String expiration,
      @RequestParam(value = "right", required = false) String right) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("underlying", ticker(underlying, "underlying"));
    params.put("expiration", required(date(expiration, "expiration"), "expiration"));
    params.put("right", right(right));
    return research.get("open-interest", params);
  }

  /**
   * Parsed contract + NBBO. Unlike the manual-entry confirm step this is not anchoring an order, so
   * a one-sided or empty book is still an answer: bid/mid/ask come back null rather than refused.
   * 503 only when the read itself returned nothing.
   */
  @GetMapping("/option-quote")
  public ResponseEntity<Map<String, Object>> optionQuote(
      @RequestParam(value = "occ", required = false) String occ) {
    ParsedOcc parsed;
    try {
      parsed = OccParser.parse(occ);
    } catch (InvalidOccException e) {
      throw new InvalidRequestException(String.valueOf(e.getMessage()));
    }

    OptionQuote q = quotes.optionQuote(parsed.occ());
    if (q == null) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(Map.of("error", "quote_unavailable", "occ", parsed.occ()));
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("occ", parsed.occ());
    body.put("underlying", parsed.ticker());
    body.put("expiry", parsed.expiry().toString());
    body.put("strike", parsed.strike());
    body.put("right", parsed.right());
    body.put("bid", q.bid());
    body.put("mid", q.mid());
    body.put("ask", q.ask());
    body.put("quoted_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
    return ResponseEntity.ok(body);
  }

  @GetMapping("/chain-expirations")
  public Map<String, Object> chainExpirations(
      @RequestParam(value = "underlying", required = false) String underlying) {
    String ticker = ticker(underlying, "underlying");

    ChainExpirations read = quotes.optionExpirations(ticker);
    if (read == null) {
      throw new UnavailableException();
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("underlying", ticker);
    body.put("spot", read.spot());
    body.put("expirations", read.expirations().stream().map(LocalDate::toString).toList());
    return body;
  }

  @GetMapping("/chain")
  public Map<String, Object> chain(
      @RequestParam(value = "underlying", required = false) String underlying,
      @RequestParam(value = "expiration", required = false) String expiration,
      @RequestParam(value = "right", required = false) String right) {
    String ticker = ticker(underlying, "underlying");
    String side = right(right);
    String expiry = required(date(expiration, "expiration"), "expiration");

    List<ChainRow> rows = quotes.optionChain(ticker, LocalDate.parse(expiry), side);
    if (rows == null) {
      throw new UnavailableException();
    }
    List<Map<String, Object>> contracts = new ArrayList<>();
    for (ChainRow r : rows) {
      Map<String, Object> c = new LinkedHashMap<>();
      c.put("occ", r.occ());
      c.put("strike", r.strike());
      c.put("bid", r.bid());
      c.put("mid", r.mid());
      c.put("ask", r.ask());
      c.put("iv", r.iv());
      c.put("delta", r.delta());
      c.put("volume", r.volume());
      contracts.add(c);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("underlying", ticker);
    body.put("expiration", expiry);
    body.put("right", side);
    body.put("spot", quotes.equityPrice(ticker));
    body.put("contracts", contracts);
    return body;
  }

  @ExceptionHandler(InvalidRequestException.class)
  ResponseEntity<Map<String, Object>> invalidRequest(InvalidRequestException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(Map.of("error", "invalid_request", "detail", e.getMessage()));
  }

  @ExceptionHandler(RateLimitedException.class)
  ResponseEntity<Map<String, Object>> rateLimited() {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .body(Map.of("error", "rate_limited"));
  }

  @ExceptionHandler(UnavailableException.class)
  ResponseEntity<Map<String, Object>> unavailable() {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(Map.of("error", "market_data_unavailable"));
  }

  // ---- input rules. Messages describe the expected shape and never echo the caller's input. ----

  /** Trimmed, upper-cased 1-6 letter symbol. Required. */
  private static String ticker(String raw, String name) {
    String ticker = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    if (!TICKER.matcher(ticker).matches()) {
      throw new InvalidRequestException(name + " must be a 1-6 letter ticker");
    }
    return ticker;
  }

  /** Comma-separated list of 1-20 symbols, normalized; null when absent. */
  private static String symbols(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String[] parts = raw.split(",", -1);
    if (parts.length > MAX_NEWS_SYMBOLS) {
      throw new InvalidRequestException("symbols must list at most " + MAX_NEWS_SYMBOLS);
    }
    List<String> out = new ArrayList<>();
    for (String part : parts) {
      out.add(ticker(part, "symbols"));
    }
    return String.join(",", out);
  }

  private static String right(String raw) {
    if (!"C".equals(raw) && !"P".equals(raw)) {
      throw new InvalidRequestException("right must be C or P");
    }
    return raw;
  }

  /** {@code YYYY-MM-DD}, canonical; null when absent. */
  private static String date(String raw, String name) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(raw.trim()).toString();
    } catch (DateTimeParseException e) {
      throw new InvalidRequestException(name + " must be YYYY-MM-DD");
    }
  }

  /** {@code YYYY-MM-DD} or an ISO-8601 instant, canonical; null when absent. */
  private static String dateOrInstant(String raw, String name) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String value = raw.trim();
    try {
      return LocalDate.parse(value).toString();
    } catch (DateTimeParseException notADate) {
      try {
        return Instant.parse(value).toString();
      } catch (DateTimeParseException e) {
        throw new InvalidRequestException(name + " must be YYYY-MM-DD or an ISO-8601 instant");
      }
    }
  }

  /** A positive integer clamped down to {@code max}; null when absent (market-data defaults it). */
  private static String limit(String raw, String name, int max) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    int value;
    try {
      value = Integer.parseInt(raw.trim());
    } catch (NumberFormatException e) {
      throw new InvalidRequestException(name + " must be a positive integer");
    }
    if (value < 1) {
      throw new InvalidRequestException(name + " must be a positive integer");
    }
    return String.valueOf(Math.min(value, max));
  }

  private static String required(String value, String name) {
    if (value == null) {
      throw new InvalidRequestException(name + " is required");
    }
    return value;
  }

  private static void putIfPresent(Map<String, String> params, String name, String value) {
    if (value != null) {
      params.put(name, value);
    }
  }

  private static class InvalidRequestException extends RuntimeException {
    InvalidRequestException(String message) {
      super(message);
    }
  }
}
