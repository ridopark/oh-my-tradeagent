package com.ohmytradeagent.tdbff.preview;

import com.ohmytradeagent.tdbff.web.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public demo's read surface (PLAN-2026-11-26-public-staging-preview.md). Reachable with a
 * preview token (bound to one tenant by {@code ServiceTokenFilter}, GET/HEAD only) or the shared
 * token. Every response is a {@link PreviewProjection}; no request parameter is accepted.
 */
@RestController
@RequestMapping("/api/preview")
class PreviewController {

  private final PreviewService service;
  private final TenantContext ctx;

  PreviewController(PreviewService service, TenantContext ctx) {
    this.service = service;
    this.ctx = ctx;
  }

  @GetMapping("/status")
  Map<String, Object> status(HttpServletRequest req) {
    return service.status(ctx.tenantId(req));
  }

  @GetMapping("/portfolio")
  Map<String, Object> portfolio(HttpServletRequest req) {
    return service.portfolio(ctx.tenantId(req));
  }

  @GetMapping("/fills")
  Map<String, Object> fills(HttpServletRequest req) {
    return items(service.fills(ctx.tenantId(req)));
  }

  @GetMapping("/orders")
  Map<String, Object> orders(HttpServletRequest req) {
    return items(service.orders(ctx.tenantId(req)));
  }

  @GetMapping("/marks")
  Map<String, Object> marks(HttpServletRequest req) {
    return service.marks(ctx.tenantId(req));
  }

  private static Map<String, Object> items(List<Map<String, Object>> rows) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("count", rows.size());
    body.put("items", rows);
    return body;
  }
}
