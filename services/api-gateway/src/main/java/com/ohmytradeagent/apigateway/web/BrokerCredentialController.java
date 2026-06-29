package com.ohmytradeagent.apigateway.web;

import com.ohmytradeagent.orchestrator.workflows.BrokerCredentialAuditWorkflow;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * UI-P2-a credential-write forward endpoint. {@code POST /broker-credentials} takes a
 * tenant-entered broker key from the dashboard server and hands it to the shared {@link
 * BrokerCredentialForwardService}, which forwards it (secret in the HTTP body only) to exec's
 * {@code POST /internal/broker-credentials}, maps the exec outcome to a coarse caller result, and
 * fires a metadata-only {@link BrokerCredentialAuditWorkflow}.
 *
 * <p><b>Dark by construction.</b> Gated on {@code broker.credentials.write.enabled=true}; with the
 * flag unset (repo default / homelab) the bean does not exist → the route 404s.
 *
 * <p><b>Auth + tenant trust.</b> {@link com.ohmytradeagent.apigateway.security.ServiceTokenFilter}
 * authenticates the caller (shared bearer) before this runs; the controller then requires a
 * validated {@code X-Tenant-Id} (no dev fallback) and asserts it equals {@code body.tenant_id} — a
 * mismatch is a cross-tenant write attempt, rejected 403 with no detail. This tenant-derivation +
 * guard is the ONLY responsibility unique to this route; the forward/audit/rate-limit pipeline is
 * shared with {@link OperatorBrokerCredentialController} via {@link
 * BrokerCredentialForwardService}.
 */
@RestController
@RequestMapping("/broker-credentials")
@ConditionalOnProperty(name = "broker.credentials.write.enabled", havingValue = "true")
public class BrokerCredentialController {

  private static final String ACTOR = "api-gateway:/broker-credentials";

  private final BrokerCredentialForwardService forwardService;
  private final TenantContext ctx;

  public BrokerCredentialController(
      BrokerCredentialForwardService forwardService, TenantContext ctx) {
    this.forwardService = forwardService;
    this.ctx = ctx;
  }

  @PostMapping
  public ResponseEntity<Map<String, Object>> write(
      HttpServletRequest req, @RequestBody BrokerCredentialForwardRequest body) {

    // (a) strict tenant — 400 if absent/blank/malformed (no dev fallback on this route).
    String tenant = ctx.requiredTenantId(req);

    // (b) cross-tenant guard — coarse 403, no detail (no oracle).
    if (body == null || !tenant.equals(body.tenantId())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    // The tenant route never echoes the broker_account_id back to the caller (version only).
    return forwardService.forward(tenant, ACTOR, body, false);
  }
}
