package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.proximity.MarketDataLivenessClient;
import com.ohmytradeagent.tdbff.proximity.MarketDataQuoteClient;
import com.ohmytradeagent.tdbff.proximity.ProximityReader;
import com.ohmytradeagent.tdbff.proximity.ProximityReader.PositionProximity;
import com.ohmytradeagent.tdbff.proximity.ProximityReader.WatchlistProximity;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/proximity} — live WS-feed liveness plus per-watchlist entry proximity and
 * per-position exit proximity for the authenticated tenant. Backs the dashboard {@code /live} view.
 */
@RestController
@RequestMapping("/api/proximity")
public class ProximityController {

  private final ProximityReader reader;
  private final MarketDataLivenessClient liveness;
  private final MarketDataQuoteClient quotes;
  private final TenantContext ctx;

  public ProximityController(
      ProximityReader reader,
      MarketDataLivenessClient liveness,
      MarketDataQuoteClient quotes,
      TenantContext ctx) {
    this.reader = reader;
    this.liveness = liveness;
    this.quotes = quotes;
    this.ctx = ctx;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> get(HttpServletRequest req) {
    String tenant = ctx.tenantId(req);
    List<WatchlistProximity> watchProx = reader.watchlist(tenant);
    // Indicative option premium per leg's resolved OCC, deduped so each OCC is fetched once.
    // Fail-soft: a null premium (market-data unreachable / OCC unresolved) renders "-".
    Map<String, BigDecimal> optionPremium = new LinkedHashMap<>();
    for (WatchlistProximity w : watchProx) {
      if (w.optionSymbol() != null && !w.optionSymbol().isBlank()) {
        optionPremium.computeIfAbsent(w.optionSymbol(), quotes::optionPremium);
      }
    }
    List<Map<String, Object>> watchlist =
        watchProx.stream().map(w -> watchlistItem(w, optionPremium)).toList();
    List<PositionProximity> positionProx = reader.positions(tenant);
    // Underlying spot per position, deduped so each distinct ticker is fetched once. Fail-soft: a
    // null price (market-data unreachable / no snapshot) just renders "-".
    Map<String, BigDecimal> underlyingSpot = new LinkedHashMap<>();
    for (PositionProximity p : positionProx) {
      String ticker = ProximityReader.underlyingTicker(p.contractSymbol());
      if (ticker != null) {
        underlyingSpot.computeIfAbsent(ticker, quotes::equityPrice);
      }
    }
    List<Map<String, Object>> positions =
        positionProx.stream().map(p -> positionItem(p, underlyingSpot)).toList();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tenant_id", tenant);
    body.put("liveness", liveness.feedHealth());
    body.put("watchlist", watchlist);
    body.put("positions", positions);
    return ResponseEntity.ok(body);
  }

  private static Map<String, Object> watchlistItem(
      WatchlistProximity w, Map<String, BigDecimal> optionPremium) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("workflow_id", w.workflowId());
    m.put("strategy_id", w.strategyId());
    m.put("ticker", w.ticker());
    m.put("direction", w.direction());
    m.put("trigger_level", w.triggerLevel());
    m.put("band_low", w.bandLow());
    m.put("band_high", w.bandHigh());
    m.put("last_price", w.lastPrice());
    m.put("state", w.state());
    m.put("distance_to_trigger_pct", w.distanceToTriggerPct());
    m.put("option_symbol", w.optionSymbol());
    m.put("option_premium", w.optionSymbol() == null ? null : optionPremium.get(w.optionSymbol()));
    return m;
  }

  private static Map<String, Object> positionItem(
      PositionProximity p, Map<String, BigDecimal> underlyingSpot) {
    String ticker = ProximityReader.underlyingTicker(p.contractSymbol());
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("workflow_id", p.workflowId());
    m.put("strategy_id", p.strategyId());
    m.put("contract_symbol", p.contractSymbol());
    m.put("underlying", ticker);
    m.put("underlying_price", ticker == null ? null : underlyingSpot.get(ticker));
    m.put("entry_premium", p.entryPremium());
    m.put("stop_level", p.stopLevel());
    m.put("target_level", p.targetLevel());
    m.put("last_bid", p.lastBid());
    m.put("peak_premium", p.peakPremium());
    m.put("trailing_armed", p.trailingArmed());
    m.put("distance_to_stop_pct", p.distanceToStopPct());
    m.put("distance_to_target_pct", p.distanceToTargetPct());
    return m;
  }
}
