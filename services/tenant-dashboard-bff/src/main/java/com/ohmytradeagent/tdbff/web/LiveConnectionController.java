package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.live.LiveConnectionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/live/connection} — the /live connection strip (PLAN-2026-10-01 P3). Tenant from
 * the session header only (the broker part reads this tenant's fill-listener row). Every part is
 * fail-soft inside {@link LiveConnectionService}, so this is always a 200 for a known tenant.
 */
@RestController
@RequestMapping("/api/live/connection")
public class LiveConnectionController {

  private final LiveConnectionService service;
  private final TenantContext ctx;

  public LiveConnectionController(LiveConnectionService service, TenantContext ctx) {
    this.service = service;
    this.ctx = ctx;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> get(HttpServletRequest req) {
    return ResponseEntity.ok(service.connection(ctx.tenantId(req)));
  }
}
