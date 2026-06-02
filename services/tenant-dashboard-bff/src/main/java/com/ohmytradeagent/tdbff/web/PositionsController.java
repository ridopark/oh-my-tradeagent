package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.positions.PositionsReader;
import com.ohmytradeagent.tdbff.positions.PositionsReader.OpenPosition;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/positions} — open positions for the authenticated tenant, all strategies. */
@RestController
@RequestMapping("/api/positions")
public class PositionsController {

  private final PositionsReader reader;
  private final TenantContext ctx;

  public PositionsController(PositionsReader reader, TenantContext ctx) {
    this.reader = reader;
    this.ctx = ctx;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> list(HttpServletRequest req) {
    String tenant = ctx.tenantId(req);
    List<OpenPosition> positions = reader.openPositions(tenant);
    List<Map<String, Object>> items = positions.stream().map(PositionsController::item).toList();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tenant_id", tenant);
    body.put("count", items.size());
    body.put("items", items);
    return ResponseEntity.ok(body);
  }

  private static Map<String, Object> item(OpenPosition p) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("workflow_id", p.workflowId());
    m.put("strategy_id", p.strategyId());
    m.put("contract_symbol", p.contractSymbol());
    m.put("remaining_qty", p.remainingQty());
    m.put("entry_premium", p.entryPremium());
    m.put("open_notional", p.openNotional());
    return m;
  }
}
