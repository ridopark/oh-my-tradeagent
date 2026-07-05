package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.invites.InviteWriterRepository;
import com.ohmytradeagent.tdbff.invites.InviteWriterRepository.DeletedIdentityCounts;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator tenant-delete teardown, dashboard store: {@code DELETE
 * /api/admin/tenants/{tenant}/dashboard-rows}. Removes a tenant's dashboard login identities (bound
 * members + open invites) — the LAST store in a tenant de-provisioning, and the only one the
 * api-gateway cannot reach directly (the BFF alone connects to the {@code dashboard} DB). The
 * api-gateway (Phase 4) is the operator entrypoint that enforces the P0–P5 live-safety
 * preconditions and confirm before it ever calls this route; this endpoint is a pure, idempotent
 * delete.
 *
 * <p>DARK-GATED via a two-name {@code @ConditionalOnProperty} (both must be {@code true}, missing =
 * fail-closed): the bean exists only when BOTH {@code operator.tenant-delete.enabled=true} AND
 * {@code dashboard.writer.enabled=true} (the latter because the delete goes through the
 * least-privilege {@code dashboard_writer} DSL). With either off the bean is absent and the route
 * 404s — and requiring both together means enabling just one flag never yields a half-wired
 * controller that would fail context startup on a missing writer DSL. Mirrors {@link
 * TenantInvitesController}.
 *
 * <p>Security: bearer-gated like every BFF request (the unconditional {@code ServiceTokenFilter} —
 * this route is deliberately NOT in {@code shouldNotFilter}), PLUS an allowlisted {@code
 * X-Operator-Id} ({@link TenantContext#requireAllowlistedOperator}): 400 if the header is
 * absent/malformed, 403 if the operator is not allowlisted — resolved BEFORE any delete.
 */
@RestController
@RequestMapping("/api/admin/tenants")
@ConditionalOnProperty(
    name = {"operator.tenant-delete.enabled", "dashboard.writer.enabled"},
    havingValue = "true")
public class TenantDashboardRowsController {

  private final InviteWriterRepository writer;
  private final TenantContext ctx;

  public TenantDashboardRowsController(InviteWriterRepository writer, TenantContext ctx) {
    this.writer = writer;
    this.ctx = ctx;
  }

  @DeleteMapping("/{tenant}/dashboard-rows")
  public ResponseEntity<Map<String, Object>> deleteDashboardRows(
      HttpServletRequest req, @PathVariable("tenant") String tenant) {
    // 400 if X-Operator-Id absent/malformed; 403 (before any delete) if not allowlisted.
    ctx.requireAllowlistedOperator(req);

    DeletedIdentityCounts counts = writer.deleteTenantIdentities(tenant);

    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("tenant_id", tenant);
    resp.put("deleted_users", counts.users());
    resp.put("deleted_invites", counts.invites());
    return ResponseEntity.ok(resp);
  }

  /**
   * Operator residual-cleanup incarnation guard (Phase 2, partial-teardown remediation): the NEWEST
   * {@code created_at} across the tenant's {@code dashboard_user} + {@code dashboard_user_invite}
   * rows, or {@code null} when the tenant has none. Read-only, idempotent. The api-gateway cleanup
   * route compares this against the last tenant-delete timestamp: a genuine residual row PREDATES
   * the delete, while a REUSED tenant_id's re-onboarding invite POSTDATES it (so cleanup must
   * refuse). No PII in the response — only the timestamp. Same dark-gate + service-token +
   * allowlisted-operator gate as the sibling DELETE.
   */
  @GetMapping("/{tenant}/dashboard-rows/newest")
  public ResponseEntity<Map<String, Object>> newestDashboardRow(
      HttpServletRequest req, @PathVariable("tenant") String tenant) {
    // 400 if X-Operator-Id absent/malformed; 403 (before any read) if not allowlisted.
    ctx.requireAllowlistedOperator(req);

    OffsetDateTime newest = writer.newestDashboardRowCreatedAt(tenant);

    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("tenant_id", tenant);
    // ISO-8601 string, or null when the tenant has no dashboard rows.
    resp.put("newest_created_at", newest == null ? null : newest.toString());
    return ResponseEntity.ok(resp);
  }
}
