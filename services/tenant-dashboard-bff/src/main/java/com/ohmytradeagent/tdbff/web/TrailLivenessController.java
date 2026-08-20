package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.positions.PositionsReader;
import com.ohmytradeagent.tdbff.positions.PositionsReader.OpenPosition;
import com.ohmytradeagent.tdbff.proximity.MarketDataLivenessClient;
import com.ohmytradeagent.tdbff.proximity.MarketDataLivenessClient.PremiumSubscriptions;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/trail-liveness} — per-position trailing-stop liveness for the authenticated
 * tenant. Backs the /live "Trailing x%" badge's pulse and its feed dot (#717).
 *
 * <p><b>Why this is not folded into {@code /api/proximity}.</b> That endpoint's position list is
 * built from the {@code exitProximity} query and drops anything whose watchlist-exit {@code armed}
 * flag is false — which is EVERY copytrade position, i.e. every real-money position there is.
 * (Verified against a live workflow: {@code armed:false} alongside {@code trailingArmed:true}; the
 * two flags name different mechanisms and only the second is the chandelier.) Widening that filter
 * would change what the existing proximity tables render, so the badge gets its own read.
 *
 * <p><b>Three states, not two.</b> {@code feed_status} is {@code "live"} / {@code "orphaned"} /
 * {@code "unknown"}. "Unknown" is load-bearing: if market-data cannot be reached we must NOT claim
 * a trail is orphaned, because the operator's response to a red badge is to re-arm a stop on a
 * real-money position. A monitoring failure must never look like a trading-safety failure.
 */
@RestController
@RequestMapping("/api/trail-liveness")
public class TrailLivenessController {

  /**
   * A contract whose last good poll is older than this is dead, not quiet. The premium poll runs at
   * 500ms (2/sec/contract), so 10s is 20 missed polls — far outside jitter, far inside the minutes
   * an honest tick-emit gap can reach. This threshold is safe ONLY because it reads the poll clock;
   * applied to the emit clock it would call a perfectly healthy trail dead all day.
   */
  private static final long FEED_STALE_AFTER_MS = 10_000L;

  private final PositionsReader reader;
  private final MarketDataLivenessClient liveness;
  private final TenantContext ctx;

  public TrailLivenessController(
      PositionsReader reader, MarketDataLivenessClient liveness, TenantContext ctx) {
    this.reader = reader;
    this.liveness = liveness;
    this.ctx = ctx;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> get(HttpServletRequest req) {
    String tenant = ctx.tenantId(req);
    List<OpenPosition> positions = reader.openPositions(tenant);
    PremiumSubscriptions subs = liveness.premiumSubscriptions();

    List<Map<String, Object>> rows = positions.stream().map(p -> row(p, subs)).toList();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tenant_id", tenant);
    body.put("market_data_reachable", subs != null);
    body.put("positions", rows);
    return ResponseEntity.ok(body);
  }

  private static Map<String, Object> row(OpenPosition p, PremiumSubscriptions subs) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("workflow_id", p.workflowId());
    m.put("contract_symbol", p.contractSymbol());
    m.put("trailing_armed", p.trailingArmed());
    m.put("trail_giveback_pct", p.trailGivebackPct());
    m.put("trail_stop_price", p.trailStopPrice());
    // The pulse driver: the client blinks when this INCREMENTS between polls. An absolute value
    // means little on its own (~50 emitted ticks a day), which is exactly why the dot below reads a
    // different clock.
    m.put("ticks_received", p.trailTicksReceived());
    m.put(
        "last_tick_observed_at",
        p.trailLastTickObservedAt() == null ? null : p.trailLastTickObservedAt().toString());
    m.put("feed_status", feedStatus(p.contractSymbol(), subs));
    return m;
  }

  /**
   * {@code live} when market-data holds a subscription for this contract AND its last successful
   * poll is recent; {@code orphaned} when market-data answered but has no live/fresh subscription;
   * {@code unknown} when market-data could not be reached at all.
   *
   * <p>Ages {@code last_poll_ok_at} against market-data's OWN {@code now}, not this pod's clock —
   * they are separate deployments and at a 500ms poll their drift is the whole signal.
   */
  private static String feedStatus(String occSymbol, PremiumSubscriptions subs) {
    if (subs == null) {
      return "unknown";
    }
    Map<String, Object> st = subs.byOcc().get(occSymbol);
    if (st == null) {
      // market-data is up and does not know this contract: the #717 orphan, stated positively.
      return "orphaned";
    }
    Object lastPoll = st.get("last_poll_ok_at");
    if (!(lastPoll instanceof String lastPollStr) || subs.now() == null) {
      return "unknown";
    }
    try {
      long ageMs =
          Instant.parse(subs.now()).toEpochMilli() - Instant.parse(lastPollStr).toEpochMilli();
      return ageMs <= FEED_STALE_AFTER_MS ? "live" : "orphaned";
    } catch (RuntimeException e) {
      return "unknown";
    }
  }
}
