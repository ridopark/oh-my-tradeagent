package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.portfolio.PortfolioService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/portfolio} — composed positions/notional/realized-PnL/equity for the tenant. */
@RestController
@RequestMapping("/api/portfolio")
public class PortfolioController {

  private final PortfolioService service;
  private final TenantContext ctx;

  public PortfolioController(PortfolioService service, TenantContext ctx) {
    this.service = service;
    this.ctx = ctx;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> portfolio(HttpServletRequest req) {
    String tenant = ctx.tenantId(req);
    return ResponseEntity.ok(service.portfolio(tenant));
  }
}
