package com.ohmytradeagent.marketdata.research;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only market research surface ({@code /md/research/*}) the tenant-dashboard BFF proxies for
 * the MCP market tools. Query params only, explicit {@code @RequestParam} names (no {@code
 * -parameters} dependency; see {@code MarketDataQuoteController}). The BFF is the validating
 * boundary; this only rejects what it cannot forward and clamps limits.
 *
 * <p>GLOBAL RATE GUARD: at most {@value #MAX_REQUESTS_PER_WINDOW} requests per fixed 60s window
 * across every route here. The Alpaca key's rate limit is shared with the option-premium poll and
 * the account kill-switch quote read, so an AI client must not be able to exhaust it.
 */
@RestController
@ConditionalOnProperty(name = "market-data.provider", havingValue = "alpaca")
public class MarketResearchController {

  static final int MAX_REQUESTS_PER_WINDOW = 120;
  private static final long WINDOW_MS = 60_000L;
  private static final Set<String> TIMEFRAMES =
      Set.of("1Min", "5Min", "15Min", "30Min", "1Hour", "1Day", "1Week", "1Month");

  private final AlpacaResearchClient client;
  private final Clock clock;
  private long windowStartMs;
  private int windowCount;

  @Autowired
  public MarketResearchController(AlpacaResearchClient client) {
    this(client, Clock.systemUTC());
  }

  MarketResearchController(AlpacaResearchClient client, Clock clock) {
    this.client = client;
    this.clock = clock;
  }

  @GetMapping("/md/research/snapshot")
  public ResponseEntity<Map<String, Object>> snapshot(@RequestParam("ticker") String ticker) {
    return guarded(() -> client.snapshot(ticker));
  }

  @GetMapping("/md/research/bars")
  public ResponseEntity<Map<String, Object>> bars(
      @RequestParam("ticker") String ticker,
      @RequestParam("timeframe") String timeframe,
      @RequestParam("start") String start,
      @RequestParam(name = "end", required = false) String end,
      @RequestParam(name = "limit", defaultValue = "500") int limit) {
    if (!TIMEFRAMES.contains(timeframe)) {
      return invalid("unknown timeframe");
    }
    return guarded(() -> client.bars(ticker, timeframe, start, end, clamp(limit, 1000)));
  }

  @GetMapping("/md/research/news")
  public ResponseEntity<Map<String, Object>> news(
      @RequestParam(name = "symbols", required = false) String symbols,
      @RequestParam(name = "start", required = false) String start,
      @RequestParam(name = "limit", defaultValue = "10") int limit) {
    return guarded(() -> client.news(symbols, start, clamp(limit, 50)));
  }

  @GetMapping("/md/research/movers")
  public ResponseEntity<Map<String, Object>> movers(
      @RequestParam(name = "top", defaultValue = "10") int top) {
    return guarded(() -> client.movers(clamp(top, 50)));
  }

  @GetMapping("/md/research/clock")
  public ResponseEntity<Map<String, Object>> clock() {
    return guarded(client::clock);
  }

  @GetMapping("/md/research/corporate-actions")
  public ResponseEntity<Map<String, Object>> corporateActions(
      @RequestParam("symbol") String symbol,
      @RequestParam("start") String start,
      @RequestParam(name = "end", required = false) String end) {
    return guarded(() -> client.corporateActions(symbol, start, end));
  }

  @GetMapping("/md/research/open-interest")
  public ResponseEntity<Map<String, Object>> openInterest(
      @RequestParam("underlying") String underlying,
      @RequestParam("expiration") String expiration,
      @RequestParam("right") String right) {
    if (!"C".equals(right) && !"P".equals(right)) {
      return invalid("right must be C or P");
    }
    return guarded(() -> client.openInterest(underlying, expiration, right));
  }

  private ResponseEntity<Map<String, Object>> guarded(Supplier<Map<String, Object>> read) {
    if (!tryAcquire()) {
      return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
          .body(Map.of("error", "rate_limited"));
    }
    try {
      return ResponseEntity.ok(read.get());
    } catch (ResearchUpstreamException e) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(Map.of("error", "upstream_unavailable"));
    }
  }

  private synchronized boolean tryAcquire() {
    long now = clock.millis();
    if (now - windowStartMs >= WINDOW_MS) {
      windowStartMs = now;
      windowCount = 0;
    }
    if (windowCount >= MAX_REQUESTS_PER_WINDOW) {
      return false;
    }
    windowCount++;
    return true;
  }

  private static int clamp(int value, int max) {
    return Math.max(1, Math.min(value, max));
  }

  private static ResponseEntity<Map<String, Object>> invalid(String detail) {
    return ResponseEntity.badRequest().body(Map.of("error", "invalid_request", "detail", detail));
  }
}
