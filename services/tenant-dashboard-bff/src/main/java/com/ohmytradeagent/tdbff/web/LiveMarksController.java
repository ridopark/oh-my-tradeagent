package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.live.MarketDataMarksClient;
import com.ohmytradeagent.tdbff.live.MarketDataMarksClient.MarksResponse;
import com.ohmytradeagent.tdbff.live.OpenOccCache;
import com.ohmytradeagent.tdbff.portfolio.BrokerPositionsClient;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/live/marks} — the 1s /live holdings poll (PLAN-2026-10-01 P3).
 *
 * <p><b>Never reaches the broker.</b> The OCC set is the tenant's open positions from {@link
 * OpenOccCache} (Temporal, cached 15s), and the marks come from market-data's display cache. The
 * broker trading API is read only on page render / the 15s refresh ({@code /api/portfolio}).
 *
 * <p>Fail-soft: market-data unreachable is a 200 with {@code market_data_reachable:false} and no
 * marks; a failed positions read is {@code occs_status:"unknown"}. {@code market_data_reachable} is
 * true only when market-data answered THIS request (it is false when there was nothing to ask for).
 * Each mark's {@code occ} is the contract symbol exactly as the portfolio rows carry it, so the
 * client joins without normalising.
 */
@RestController
@RequestMapping("/api/live/marks")
public class LiveMarksController {

  private final OpenOccCache occs;
  private final MarketDataMarksClient marksClient;
  private final TenantContext ctx;

  public LiveMarksController(
      OpenOccCache occs, MarketDataMarksClient marksClient, TenantContext ctx) {
    this.occs = occs;
    this.marksClient = marksClient;
    this.ctx = ctx;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> get(HttpServletRequest req) {
    String tenant = ctx.tenantId(req);
    OpenOccCache.Snapshot snap = occs.get(tenant);

    // compact OCC -> the row-form symbol(s) that normalise to it.
    Map<String, List<String>> rowFormsByCompact = new LinkedHashMap<>();
    for (String symbol : snap.contractSymbols()) {
      String compact = BrokerPositionsClient.compactOcc(symbol);
      if (compact != null) {
        rowFormsByCompact.computeIfAbsent(compact, k -> new ArrayList<>()).add(symbol);
      }
    }

    MarksResponse resp =
        rowFormsByCompact.isEmpty()
            ? null
            : marksClient.marks(new ArrayList<>(rowFormsByCompact.keySet()));

    List<Map<String, Object>> marks = new ArrayList<>();
    if (resp != null) {
      for (Map<String, Object> mark : resp.marks()) {
        List<String> rowForms =
            rowFormsByCompact.get(BrokerPositionsClient.compactOcc((String) mark.get("occ")));
        if (rowForms == null) {
          continue; // not a contract this tenant holds
        }
        for (String rowForm : rowForms) {
          Map<String, Object> out = new LinkedHashMap<>(mark);
          out.put("occ", rowForm);
          marks.add(out);
        }
      }
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("now", resp != null && resp.now() != null ? resp.now() : Instant.now().toString());
    body.put("market_data_reachable", resp != null);
    body.put("occs_status", snap.ok() ? "ok" : "unknown");
    body.put("marks", marks);
    return ResponseEntity.ok(body);
  }
}
