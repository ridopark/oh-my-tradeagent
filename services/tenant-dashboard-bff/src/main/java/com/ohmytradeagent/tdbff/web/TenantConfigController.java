package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.platform.TenantConfigReader;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/tenant-config} — the tenant's account-level daily-loss cap ({@code
 * account_daily_loss_threshold} / {@code account_daily_loss_pct}) + {@code version} + field-class
 * metadata. Read-only (Phase 2); the tighten-only write surface is Phase 3. The tenant is resolved
 * FAIL-CLOSED from {@code X-Tenant-Id} via {@link TenantContext} (401 on missing/blank — never a
 * {@code dev} fallback), so a caller can never read another tenant's cap.
 */
@RestController
@RequestMapping("/api/tenant-config")
public class TenantConfigController {

  private final TenantConfigReader reader;
  private final TenantContext ctx;

  public TenantConfigController(TenantConfigReader reader, TenantContext ctx) {
    this.reader = reader;
    this.ctx = ctx;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> get(HttpServletRequest req) {
    String tenant = ctx.tenantId(req);
    TenantConfigReader.TenantCap cap = reader.capFor(tenant);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("account_daily_loss_threshold", cap.accountDailyLossThreshold());
    body.put("account_daily_loss_pct", cap.accountDailyLossPct());
    body.put("version", cap.version());
    // EXPOSURE (tighten-only) display metadata so the UI reuses its existing badge model.
    body.put("field_classes", TenantConfigReader.FIELD_CLASSES);
    return ResponseEntity.ok(body);
  }
}
