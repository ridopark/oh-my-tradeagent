package com.ohmytradeagent.exec.web;

import com.ohmytradeagent.exec.broker.alpaca.BrokerCredentialWriter;
import com.ohmytradeagent.exec.broker.alpaca.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * P6-c dark credential-write endpoint. {@code POST /internal/broker-credentials} wraps the inert
 * P6-b {@link BrokerCredentialWriter#save} UNCHANGED (validate-on-entry → encrypt → CAS) and
 * exposes it as a single authenticated HTTP POST so a future api-gateway caller (UI-P2) can persist
 * a tenant-entered broker key WITHOUT routing the secret through Temporal history (MF-7).
 *
 * <p><b>Dark by construction.</b> Gated on {@code broker.creds.source=db} AND an {@code alpaca-*}
 * impl, so on a homelab pod (selector at {@code env}) this bean does not exist → the endpoint 404s
 * → zero new attack surface. The {@link ExecAdminTokenFilter} is gated identically.
 *
 * <p><b>Auth model.</b> The {@link ExecAdminTokenFilter} authenticates the CALLER (shared bearer
 * token) before this handler runs; this controller then trusts the {@code X-Tenant-Id} header it
 * injects and asserts {@code body.tenant_id == X-Tenant-Id} — a mismatch is a cross-tenant write
 * attempt and rejected 403. The authenticated tenant is recorded as the audit {@code actor}.
 *
 * <p><b>Secret hygiene (MF-7).</b> The request body, api-key, and secret are NEVER logged, NEVER
 * echoed in the response, and NEVER placed in an exception message. Writer rejections are mapped to
 * a coarse, non-secret reason so the endpoint is not an auth oracle.
 *
 * <p><b>Deferred to UI-P2</b> (when the route becomes reachable from api-gateway): rate-limit /
 * lockout on the underlying {@code /v2/account} probe, and TLS/mTLS hardening between
 * api-gateway↔exec. There is no NetworkPolicy here because there is no caller yet.
 */
@RestController
@RequestMapping("/internal/broker-credentials")
@ConditionalOnExpression("'${broker.impl:}'.startsWith('alpaca-')")
@ConditionalOnProperty(name = "broker.creds.source", havingValue = "db")
public class BrokerCredentialAdminController {

  private static final Logger log = LoggerFactory.getLogger(BrokerCredentialAdminController.class);

  private final BrokerCredentialWriter writer;

  public BrokerCredentialAdminController(BrokerCredentialWriter writer) {
    this.writer = writer;
  }

  @PostMapping
  public ResponseEntity<BrokerCredentialWriteResponse> write(
      @RequestHeader("X-Tenant-Id") String callerTenantId,
      @RequestBody BrokerCredentialWriteRequest body) {

    // Tenant authorization: the caller is already authenticated by the filter; assert it is not
    // writing another tenant's credentials. NEVER log the body — only the coarse identifiers.
    if (body == null || body.tenantId() == null || !body.tenantId().equals(callerTenantId)) {
      // No cross-tenant writes. Coarse 403, no body detail (no oracle, no secret).
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    String actor = callerTenantId;
    try {
      long version =
          writer.save(
              body.tenantId(),
              body.provider(),
              body.apiKeyId(),
              body.apiSecretKey(),
              body.baseUrl(),
              body.wsUrl(),
              body.declaredAccountId(),
              body.expectedVersion(),
              actor);
      // Success log carries only non-secret identifiers (tenant/provider/version), never the body.
      log.info(
          "broker credential write accepted tenant={} provider={} version={}",
          body.tenantId(),
          body.provider(),
          version);
      return ResponseEntity.ok(new BrokerCredentialWriteResponse(version));
    } catch (OptimisticLockException e) {
      // Stale version → 409. The writer message may name tenant/provider/version (no key); we do
      // NOT propagate it to the body to keep the response free of any detail.
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    } catch (IllegalStateException e) {
      // Any writer rejection (refuse-live / missing creds / paper-live host mismatch / account
      // mismatch) → 422 with a coarse reason. We deliberately do NOT reveal which specific check
      // failed (it is an auth oracle) and never surface the writer's message (could name inputs).
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "credential_rejected");
    } catch (RuntimeException e) {
      // Catch-all → 500 with no detail. Log only the exception type, never the message/stack with
      // the body — the body and any wrapped input must never reach a log line (MF-7).
      log.error(
          "broker credential write failed tenant={} provider={} cause={}",
          body.tenantId(),
          body.provider(),
          e.getClass().getName());
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }
}
