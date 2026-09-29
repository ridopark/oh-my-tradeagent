package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.platform.TenantStrategyResolver;
import com.ohmytradeagent.tdbff.trades.SignalsReader;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/signals?limit=} — recently ACCEPTED entry signals for the tenant, newest first,
 * so the /live manual-entry box can offer contracts the system has actually signalled instead of
 * requiring a hand-typed 19-character OCC. Read-only; mirrors {@link TradesController}.
 */
@RestController
@RequestMapping("/api/signals")
public class SignalsController {

  private final SignalsReader reader;
  private final TenantStrategyResolver strategyResolver;
  private final TenantContext ctx;

  public SignalsController(
      SignalsReader reader, TenantStrategyResolver strategyResolver, TenantContext ctx) {
    this.reader = reader;
    this.strategyResolver = strategyResolver;
    this.ctx = ctx;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> list(
      HttpServletRequest req,
      @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
    String tenant = ctx.tenantId(req);
    List<String> strategyIds = strategyResolver.strategyIdsForTenant(tenant);
    List<Map<String, Object>> items = reader.signals(tenant, strategyIds, limit);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tenant_id", tenant);
    body.put("count", items.size());
    body.put("items", items);
    return ResponseEntity.ok(body);
  }
}
