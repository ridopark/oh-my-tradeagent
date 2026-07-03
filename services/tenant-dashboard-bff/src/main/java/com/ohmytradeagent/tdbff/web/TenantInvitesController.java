package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.invites.InviteWriterRepository;
import com.ohmytradeagent.tdbff.invites.InviteWriterRepository.InviteRecord;
import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator create-invite: {@code POST /api/admin/tenant-invites}. The onboard form calls this to
 * grant a person login access to a tenant by EMAIL, before that person has ever signed in. Records
 * an OPEN invite; the dashboard's signIn later matches the person's provider-verified email to the
 * invite and binds their identity (Phase 3, via {@link ProvisioningBindController}).
 *
 * <p>DARK-GATED via a two-name {@code @ConditionalOnProperty} (both must be {@code true}, missing =
 * fail-closed): the bean exists only when BOTH {@code operator.tenant-invite.enabled=true} AND
 * {@code dashboard.writer.enabled=true} (the latter because the write goes through the
 * least-privilege {@code dashboard_writer} DSL). With either off the bean is absent and the route
 * 404s — and requiring both together means enabling just one flag never yields a half-wired
 * controller that would fail context startup on a missing writer DSL.
 *
 * <p>Security: bearer-gated like every BFF request (the unconditional {@code ServiceTokenFilter}),
 * PLUS an allowlisted {@code X-Operator-Id} ({@link TenantContext#requireAllowlistedOperator}): 400
 * if the header is absent/malformed, 403 if the operator is not allowlisted — resolved BEFORE any
 * tenant is validated or written. The invited row is member-only by construction; it can never
 * confer operator.
 */
@RestController
@RequestMapping("/api/admin/tenant-invites")
@ConditionalOnProperty(
    name = {"operator.tenant-invite.enabled", "dashboard.writer.enabled"},
    havingValue = "true")
public class TenantInvitesController {

  // Conservative "plausible email": one @, non-empty local + domain, a dot in the domain, no
  // whitespace. Not RFC-5322-complete on purpose — the real proof is the provider-verified email at
  // bind time; this only rejects obvious garbage before a write.
  private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

  private final DbStrategyConfigReader strategyConfigReader;
  private final InviteWriterRepository invites;
  private final TenantContext ctx;
  private final int ttlDays;

  public TenantInvitesController(
      DbStrategyConfigReader strategyConfigReader,
      InviteWriterRepository invites,
      TenantContext ctx,
      @Value("${operator.tenant-invite.ttl-days:7}") int ttlDays) {
    this.strategyConfigReader = strategyConfigReader;
    this.invites = invites;
    this.ctx = ctx;
    this.ttlDays = ttlDays;
  }

  @PostMapping
  public ResponseEntity<Map<String, Object>> create(
      HttpServletRequest req, @RequestBody(required = false) Map<String, Object> body) {
    // 400 if X-Operator-Id absent/malformed; 403 (before any body validation or write) if the
    // operator is not allowlisted.
    String operator = ctx.requireAllowlistedOperator(req);

    String email = InviteWriterRepository.normalizeEmail(RequestBodies.str(body, "email"));
    String rawTenant = RequestBodies.str(body, "tenant_id");
    String tenantId = rawTenant == null ? "" : rawTenant.trim();

    if (!EMAIL.matcher(email).matches()) {
      return error(HttpStatus.BAD_REQUEST, "invalid_email");
    }
    if (tenantId.isEmpty()) {
      return error(HttpStatus.BAD_REQUEST, "missing_tenant_id");
    }
    // The invite's tenant MUST be a real tenant — reject an invite into a non-existent tenant.
    if (!strategyConfigReader.tenantExists(tenantId)) {
      return error(HttpStatus.UNPROCESSABLE_ENTITY, "unknown_tenant");
    }

    InviteRecord invite = invites.createInvite(email, tenantId, operator, ttlDays);

    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("invite_id", invite.id().toString());
    resp.put("tenant_id", invite.tenantId());
    resp.put("email", invite.email());
    resp.put("expires_at", invite.expiresAt().toString());
    return ResponseEntity.ok(resp);
  }

  private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code) {
    return ResponseEntity.status(status).body(Map.of("error", code));
  }
}
