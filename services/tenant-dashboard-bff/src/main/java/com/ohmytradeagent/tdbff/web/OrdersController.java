package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.orders.OrdersReader;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/orders?limit=} — order-journal history (all states) for the tenant. */
@RestController
@RequestMapping("/api/orders")
public class OrdersController {

  private final OrdersReader reader;
  private final TenantContext ctx;

  public OrdersController(OrdersReader reader, TenantContext ctx) {
    this.reader = reader;
    this.ctx = ctx;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> list(
      HttpServletRequest req,
      @RequestParam(value = "limit", required = false, defaultValue = "100") int limit) {
    String tenant = ctx.tenantId(req);
    List<Map<String, Object>> items = reader.orders(tenant, limit);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tenant_id", tenant);
    body.put("count", items.size());
    body.put("items", items);
    return ResponseEntity.ok(body);
  }
}
