package com.ohmytradeagent.exec.web;

import com.ohmytradeagent.exec.broker.alpaca.BrokerCredentialWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Dark {@code DELETE /internal/broker-credentials} teardown route. Wraps {@link
 * BrokerCredentialWriter#delete} as an authenticated HTTP DELETE so a future operator tenant-delete
 * flow (api-gateway) can remove a dark, never-traded tenant's stored broker credentials. Idempotent
 * by construction: deleting an absent row returns {@code {"deleted":0}} with 200.
 *
 * <p><b>Separate bean, gated on an extra flag (dark by construction).</b> The write endpoint lives
 * in {@link BrokerCredentialAdminController}, gated on {@code broker.creds.source=db} AND an {@code
 * alpaca-*} impl. This delete route adds a THIRD condition — {@code
 * broker.credentials.delete.enabled} (default false) — so it is a distinct bean that stays absent
 * (route 404s, even for an authenticated caller) until an operator flips the flag, WITHOUT
 * disabling the always-on write endpoint. A per-method gate on the shared controller is impossible
 * (a class-level {@code @Conditional} gates the whole bean), so the delete route is its own
 * conditional controller.
 *
 * <p><b>Auth model (unchanged).</b> The route sits under the {@code /internal/broker-credentials}
 * prefix, so the always-on, method-agnostic {@link ExecAdminTokenFilter} authenticates the caller
 * (shared bearer token) before this handler runs; a missing/wrong token is 401 at the filter. This
 * controller then asserts {@code body.tenant_id == X-Tenant-Id} — a cross-tenant delete is 403.
 *
 * <p><b>Secret hygiene.</b> The delete reads no key material; the request/response carry only
 * {@code (tenant_id, provider)} and a row count. Nothing secret is logged or echoed.
 */
@RestController
@RequestMapping("/internal/broker-credentials")
@ConditionalOnExpression(
    "'${broker.impl:}'.startsWith('alpaca-') and ${broker.credentials.delete.enabled:false}")
@ConditionalOnProperty(name = "broker.creds.source", havingValue = "db")
public class BrokerCredentialDeleteAdminController {

  private static final Logger log =
      LoggerFactory.getLogger(BrokerCredentialDeleteAdminController.class);

  private final BrokerCredentialWriter writer;

  public BrokerCredentialDeleteAdminController(BrokerCredentialWriter writer) {
    this.writer = writer;
  }

  @DeleteMapping
  public ResponseEntity<BrokerCredentialDeleteResponse> delete(
      @RequestHeader("X-Tenant-Id") String callerTenantId,
      @RequestBody BrokerCredentialDeleteRequest body) {

    // No cross-tenant deletes: the caller is already authenticated by the filter; assert it is not
    // deleting another tenant's credential row. Coarse 403, no detail.
    if (body == null || body.tenantId() == null || !body.tenantId().equals(callerTenantId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    if (body.provider() == null || body.provider().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }

    int deleted = writer.delete(body.tenantId(), body.provider());
    log.info(
        "broker credential delete accepted tenant={} provider={} deleted={}",
        body.tenantId(),
        body.provider(),
        deleted);
    return ResponseEntity.ok(new BrokerCredentialDeleteResponse(deleted));
  }
}
