package com.ohmytradeagent.apigateway.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phase I-1c (operator-account-onboarding) operator-scoped credential-write endpoint. {@code POST
 * /admin/tenants/{tenant}/broker-credentials} — lets an operator paste a NEW tenant's broker
 * api-key/secret during onboarding. The (tenant) comes from the PATH (operator-scoped — the
 * operator does not own the tenant, so there is no X-Tenant-Id to bind), and the operator id comes
 * from {@code X-Operator-Id}. Delegates the actual forward/audit/rate-limit pipeline to the shared
 * {@link BrokerCredentialForwardService} (identical to the tenant route), differing ONLY in (1)
 * path-tenant + operator auth and (2) returning the non-secret authenticated {@code
 * broker_account_id} so the onboarding UI can confirm the keys reached the intended Alpaca account.
 *
 * <p><b>Dark by construction.</b> Gated on {@code operator.credential-write.enabled=true}; with the
 * flag unset (repo default / homelab) the bean does not exist → the route 404s. NO repo manifest
 * sets it true. The flag is also in {@link
 * com.ohmytradeagent.apigateway.security.ServiceTokenFilter}'s {@code @ConditionalOnExpression}, so
 * enabling it alone still bearer-gates this {@code /admin/tenants/} route.
 *
 * <p><b>Paper-only in this phase.</b> The exec credential writer REFUSES any {@code -live}
 * broker_target while in DB-creds mode, so an operator can paste keys only for a paper target here;
 * real-money arming stays behind the coupled-creds-lift (Phase E).
 */
@RestController
@RequestMapping("/admin/tenants")
@ConditionalOnProperty(name = "operator.credential-write.enabled", havingValue = "true")
public class OperatorBrokerCredentialController {

  private final BrokerCredentialForwardService forwardService;
  private final TenantContext ctx;

  public OperatorBrokerCredentialController(
      BrokerCredentialForwardService forwardService, TenantContext ctx) {
    this.forwardService = forwardService;
    this.ctx = ctx;
  }

  @PostMapping("/{tenant}/broker-credentials")
  public ResponseEntity<Map<String, Object>> write(
      HttpServletRequest req,
      @PathVariable("tenant") String tenant,
      @RequestBody BrokerCredentialForwardRequest body) {

    String operator = ctx.operatorId(req); // 400 if X-Operator-Id absent

    // The path tenant flows into the exec X-Tenant-Id header AND the audit workflow id — reject a
    // malformed value (400) before it can corrupt either, using TenantContext's canonical charset.
    if (!ctx.isValidTenantId(tenant)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }

    // Cross-tenant guard — the body's declared tenant must match the path target; coarse 403, no
    // detail (no oracle). Unlike the tenant route the trusted tenant is the PATH, not a header.
    if (body == null || !tenant.equals(body.tenantId())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    // Return the authenticated broker_account_id so the onboarding UI can confirm the keys landed
    // on the intended account (NON-secret read-back).
    return forwardService.forward(tenant, "operator:" + operator, body, true);
  }
}
