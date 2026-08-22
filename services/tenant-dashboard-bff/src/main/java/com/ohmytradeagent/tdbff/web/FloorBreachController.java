package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader;
import com.ohmytradeagent.tdbff.positions.PositionsReader;
import com.ohmytradeagent.tdbff.positions.PositionsReader.OpenPosition;
import com.ohmytradeagent.tdbff.proximity.MarketDataQuoteClient;
import com.ohmytradeagent.tdbff.proximity.MarketDataQuoteClient.OptionQuote;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issue #779: {@code GET /api/floor-breach} — per-position floor-breach state for the authenticated
 * tenant. Backs the /live red "FLOOR BREACH -NN%" badge (the level-triggered UI half of the #779
 * alert; the edge-triggered Discord half lives in the orchestrator's {@code FloorBreachAlertLoop}).
 *
 * <p><b>Why a separate endpoint, not folded into {@code /api/trail-liveness}:</b> trail-liveness is
 * polled at 4s per open tab; adding N market-data quote HTTP calls to it would multiply load for a
 * state that moves over hours. This endpoint is polled at 30s.
 *
 * <p><b>Three states, not two</b> ({@code TrailLivenessController}'s load-bearing rule): {@code
 * floor_status} is {@code "breach"} / {@code "ok"} / {@code "unknown"}. A quote-client error or a
 * missing bid maps to {@code "unknown"}, NEVER to {@code "ok"} — a monitoring failure must never
 * look like an all-clear on a real-money position.
 *
 * <p>The formula mirrors the orchestrator's {@code FloorBreachEvaluator} semantics in the BFF's
 * transport terms (the BFF deliberately has no orchestrator compile dependency, per {@code
 * PositionStateView}'s javadoc): breach iff BID ≤ entry x (1 − threshold); bid 0 with a live ask is
 * a -100% breach; bid 0 on a dead book is unknown. This endpoint is READ-ONLY — it renders state
 * and never touches Temporal beyond the {@code PositionsReader} queries.
 */
@RestController
@RequestMapping("/api/floor-breach")
public class FloorBreachController {

  private static final Logger log = LoggerFactory.getLogger(FloorBreachController.class);

  /** Mirrors the orchestrator default for an absent/unreadable {@code floor_breach_alert_pct}. */
  static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("0.50");

  private final PositionsReader reader;
  private final MarketDataQuoteClient quotes;
  private final DbStrategyConfigReader configReader;
  private final TenantContext ctx;

  public FloorBreachController(
      PositionsReader reader,
      MarketDataQuoteClient quotes,
      DbStrategyConfigReader configReader,
      TenantContext ctx) {
    this.reader = reader;
    this.quotes = quotes;
    this.configReader = configReader;
    this.ctx = ctx;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> get(HttpServletRequest req) {
    String tenant = ctx.tenantId(req);
    List<OpenPosition> positions = reader.openPositions(tenant);
    List<Map<String, Object>> rows = positions.stream().map(p -> row(tenant, p)).toList();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tenant_id", tenant);
    body.put("positions", rows);
    return ResponseEntity.ok(body);
  }

  private Map<String, Object> row(String tenant, OpenPosition p) {
    BigDecimal threshold = threshold(tenant, p.strategyId());
    BigDecimal entry = p.entryPremium();
    BigDecimal floorLine =
        entry == null ? null : entry.multiply(BigDecimal.ONE.subtract(threshold));
    OptionQuote quote = safeQuote(p.contractSymbol());
    BigDecimal bid = quote == null ? null : quote.bid();

    String status;
    BigDecimal lossPct = null;
    if (entry == null || entry.signum() <= 0 || bid == null) {
      status = "unknown";
    } else if (bid.signum() == 0) {
      BigDecimal ask = quote.ask();
      if (ask == null || ask.signum() == 0) {
        // Dead book: an unfiltered snapshot with neither side is untrustworthy, not worthless.
        status = "unknown";
      } else {
        status = "breach";
        lossPct = BigDecimal.ONE;
      }
    } else if (bid.compareTo(floorLine) <= 0) {
      status = "breach";
      lossPct = entry.subtract(bid).divide(entry, MathContext.DECIMAL64);
    } else {
      status = "ok";
    }

    Map<String, Object> m = new LinkedHashMap<>();
    m.put("workflow_id", p.workflowId());
    m.put("contract_symbol", p.contractSymbol());
    m.put("floor_status", status);
    m.put("loss_pct", lossPct);
    m.put("entry_premium", entry);
    m.put("current_bid", bid);
    m.put("floor_line", floorLine);
    return m;
  }

  /** The per-strategy threshold; any read failure falls back to the 0.50 default. Never throws. */
  private BigDecimal threshold(String tenant, String strategyId) {
    try {
      BigDecimal configured = configReader.floorBreachAlertPct(tenant, strategyId);
      if (configured == null
          || configured.signum() <= 0
          || configured.compareTo(BigDecimal.ONE) >= 0) {
        return DEFAULT_THRESHOLD;
      }
      return configured;
    } catch (RuntimeException e) {
      log.warn(
          "floor-breach threshold read failed tenant={} strategy={} (using default)",
          tenant,
          strategyId);
      return DEFAULT_THRESHOLD;
    }
  }

  /** The NBBO snapshot, or {@code null} on any failure — which the caller maps to "unknown". */
  private OptionQuote safeQuote(String occ) {
    try {
      return quotes.optionQuote(occ);
    } catch (RuntimeException e) {
      log.warn("floor-breach quote read failed occ={}: {}", occ, e.getMessage());
      return null;
    }
  }
}
