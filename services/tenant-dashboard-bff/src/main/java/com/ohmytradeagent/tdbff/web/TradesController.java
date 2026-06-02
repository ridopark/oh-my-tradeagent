package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.platform.TenantStrategyResolver;
import com.ohmytradeagent.tdbff.trades.TradesReader;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/trades?since=&limit=} — confirmed fills for the tenant, all strategies. */
@RestController
@RequestMapping("/api/trades")
public class TradesController {

  private final TradesReader reader;
  private final TenantStrategyResolver strategyResolver;
  private final TenantContext ctx;

  public TradesController(
      TradesReader reader, TenantStrategyResolver strategyResolver, TenantContext ctx) {
    this.reader = reader;
    this.strategyResolver = strategyResolver;
    this.ctx = ctx;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> list(
      HttpServletRequest req,
      @RequestParam(value = "since", required = false) String since,
      @RequestParam(value = "limit", required = false, defaultValue = "100") int limit) {
    String tenant = ctx.tenantId(req);
    List<String> strategyIds = strategyResolver.strategyIdsForTenant(tenant);
    List<Map<String, Object>> items = reader.trades(tenant, strategyIds, since, limit);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tenant_id", tenant);
    body.put("count", items.size());
    body.put("items", items);
    return ResponseEntity.ok(body);
  }
}
