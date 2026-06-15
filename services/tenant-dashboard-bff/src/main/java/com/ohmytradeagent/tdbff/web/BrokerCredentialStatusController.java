package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.credentials.BrokerCredentialStatusReader;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/broker-credentials/status} — non-secret credential status for the tenant
 * (configured / version / account). Returns no secret material; see {@link
 * BrokerCredentialStatusReader}.
 */
@RestController
@RequestMapping("/api/broker-credentials")
public class BrokerCredentialStatusController {

  private final BrokerCredentialStatusReader reader;
  private final TenantContext ctx;

  public BrokerCredentialStatusController(BrokerCredentialStatusReader reader, TenantContext ctx) {
    this.reader = reader;
    this.ctx = ctx;
  }

  @GetMapping("/status")
  public ResponseEntity<Map<String, Object>> status(HttpServletRequest req) {
    String tenant = ctx.tenantId(req);
    List<Map<String, Object>> items = reader.statuses(tenant);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tenant_id", tenant);
    body.put("count", items.size());
    body.put("items", items);
    return ResponseEntity.ok(body);
  }
}
