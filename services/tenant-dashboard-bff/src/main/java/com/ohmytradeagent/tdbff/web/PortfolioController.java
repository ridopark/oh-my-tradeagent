package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.portfolio.PortfolioCache;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/portfolio} — composed positions/notional/realized-PnL/equity for the tenant.
 * Served through {@link PortfolioCache} (10s, single-flight per tenant) so concurrent /live viewers
 * share one broker/Temporal read.
 */
@RestController
@RequestMapping("/api/portfolio")
public class PortfolioController {

  private final PortfolioCache cache;
  private final TenantContext ctx;

  public PortfolioController(PortfolioCache cache, TenantContext ctx) {
    this.cache = cache;
    this.ctx = ctx;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> portfolio(HttpServletRequest req) {
    String tenant = ctx.tenantId(req);
    return ResponseEntity.ok(cache.portfolio(tenant));
  }
}
