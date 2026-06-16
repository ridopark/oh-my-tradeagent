package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.platform.StrategyConfigReader;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * UI-P3-a: {@code GET /api/strategy-config} — the tenant's editable strategy config(s) + {@code
 * version} (for the future write path's optimistic CAS) + field-class metadata. Read-only; no
 * secret material (broker keys live in a separate table). The write surface is UI-P3-b.
 */
@RestController
@RequestMapping("/api/strategy-config")
public class StrategyConfigController {

  private final StrategyConfigReader reader;
  private final TenantContext ctx;

  public StrategyConfigController(StrategyConfigReader reader, TenantContext ctx) {
    this.reader = reader;
    this.ctx = ctx;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> get(HttpServletRequest req) {
    String tenant = ctx.tenantId(req);
    List<Map<String, Object>> items = reader.configsForTenant(tenant);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tenant_id", tenant);
    body.put("count", items.size());
    // Field-class metadata is constant across strategies; surface once. Unlisted fields are SAFE.
    body.put("field_classes", StrategyConfigReader.FIELD_CLASSES);
    body.put("items", items);
    return ResponseEntity.ok(body);
  }
}
