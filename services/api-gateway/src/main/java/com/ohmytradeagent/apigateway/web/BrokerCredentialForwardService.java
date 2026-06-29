package com.ohmytradeagent.apigateway.web;

import com.ohmytradeagent.apigateway.security.CredentialWriteLimiter;
import com.ohmytradeagent.contract.BrokerCredentialAuditRequest;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.workflows.BrokerCredentialAuditWorkflow;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared credential-write forward pipeline behind {@link BrokerCredentialController}
 * (tenant-scoped, {@code POST /broker-credentials}) AND {@link OperatorBrokerCredentialController}
 * (operator-scoped, {@code POST /admin/tenants/{tenant}/broker-credentials}). The two routes differ
 * ONLY in how they derive (tenant, actor) — everything after that (rate-limit/lockout, forward to
 * exec, exec-status → coarse outcome, metadata-only audit) is identical, so it lives here once
 * rather than in two divergent copies of security-sensitive code.
 *
 * <p><b>Dark by construction.</b> Gated on {@code broker.credentials.write.enabled=true} OR {@code
 * operator.credential-write.enabled=true} (either route's flag brings up the shared pipeline); with
 * both unset the bean does not exist.
 *
 * <p><b>MF-7.</b> The request body, api-key, and secret are NEVER logged (the request record's
 * {@code toString} is redacted), NEVER echoed in the response, and NEVER placed on the Temporal
 * payload (the audit request carries only non-secret metadata). The secret rides ONLY the
 * api-gateway→exec HTTP body. The {@code broker_account_id} (the authenticated account number exec
 * reads back) is NON-secret metadata — fed into the SAVED audit always, and returned to the caller
 * only when {@code includeBrokerAccountId} is set (the operator onboarding read-back).
 *
 * <p><b>Rate-limit + lockout (UI-P2-c).</b> Delegated to {@link CredentialWriteLimiter} (keyed by
 * tenant only): a per-tenant minute-window cap (the exec {@code /v2/account} probe is reachable
 * through this path; bounding attempts prevents it being used as a key-testing oracle or a cost/DoS
 * lever) PLUS a lockout armed by repeated validation rejections. Refused → 429, no forward, no
 * audit.
 */
@Component
@ConditionalOnExpression(
    "${broker.credentials.write.enabled:false} or ${operator.credential-write.enabled:false}")
public class BrokerCredentialForwardService {

  private static final Logger log = LoggerFactory.getLogger(BrokerCredentialForwardService.class);
  private static final String AUDIT_TASK_QUEUE = "orchestrator-core";

  private final RestClient execRestClient;
  private final WorkflowClient workflowClient;
  private final Clock clock;
  private final CredentialWriteLimiter limiter;
  private final Counter auditStartFailures;

  public BrokerCredentialForwardService(
      RestClient execRestClient,
      WorkflowClient workflowClient,
      Clock clock,
      CredentialWriteLimiter limiter,
      MeterRegistry meterRegistry) {
    this.execRestClient = execRestClient;
    this.workflowClient = workflowClient;
    this.clock = clock;
    this.limiter = limiter;
    this.auditStartFailures =
        Counter.builder("broker_credential_audit_start_failures")
            .description("count of credential-write audit-workflow starts that failed to dispatch")
            .register(meterRegistry);
  }

  /**
   * Runs the rate-limit → forward → audit pipeline for an already-resolved (tenant, actor). The
   * caller (controller) is responsible for authenticating the request and validating that {@code
   * tenant} is the legitimate target (the tenant route binds X-Tenant-Id; the operator route binds
   * the path + X-Operator-Id) BEFORE calling this. {@code body} is assumed non-null.
   *
   * @param includeBrokerAccountId when true, a SAVED response also returns the non-secret {@code
   *     broker_account_id} (the operator onboarding read-back); the tenant route returns {@code
   *     version} only.
   */
  public ResponseEntity<Map<String, Object>> forward(
      String tenant,
      String actor,
      BrokerCredentialForwardRequest body,
      boolean includeBrokerAccountId) {

    // (c) rate-limit / lockout — refused → 429, no forward, no audit.
    if (!limiter.tryAcquire(tenant)) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
    }

    // (d) forward to exec, capturing the status without letting a non-2xx throw before mapping.
    ExecOutcome exec = forwardToExec(tenant, body);

    // Feed the outcome back to the limiter (validation rejects may arm a lockout; a SAVED resets
    // the streak) BEFORE the audit workflow, so the lockout decision is recorded even if the audit
    // start fails. Keyed by tenant only — no key material crosses into the limiter (MF-7).
    limiter.recordOutcome(tenant, exec.outcome());

    // (e) map exec status → caller response + audit outcome.
    BrokerCredentialAuditRequest.ChangeType changeType =
        body.expectedVersion() == 0
            ? BrokerCredentialAuditRequest.ChangeType.CREATE
            : BrokerCredentialAuditRequest.ChangeType.ROTATE;

    String correlationId =
        (body.correlationId() != null && !body.correlationId().isBlank())
            ? body.correlationId()
            : UUID.randomUUID().toString();

    // (f) build the metadata-only audit request (ZERO key material).
    BrokerCredentialAuditRequest auditRequest = new BrokerCredentialAuditRequest();
    auditRequest.setSchemaVersion(1L);
    auditRequest.setTenantId(tenant);
    auditRequest.setProvider(body.provider());
    auditRequest.setChangeType(changeType);
    auditRequest.setOutcome(exec.outcome());
    auditRequest.setActor(actor);
    auditRequest.setOccurredAt(OffsetDateTime.now(clock.withZone(java.time.ZoneOffset.UTC)));
    auditRequest.setCorrelationId(correlationId);
    if (exec.outcome() == BrokerCredentialAuditRequest.Outcome.SAVED && exec.body() != null) {
      auditRequest.setBrokerAccountId(exec.body().brokerAccountId());
      auditRequest.setCredentialVersion(exec.body().version());
      // Intentional int→Long: exec's wire field is int; the contract's kek_version is Long.
      auditRequest.setKekVersion((long) exec.body().kekVersion());
    }

    // (g) start the audit workflow NON-BLOCKING; a failure here must not fail the write result.
    startAuditWorkflow(tenant, correlationId, auditRequest);

    // (h) coarse result only — never echo the key, return only the write outcome.
    if (exec.outcome() == BrokerCredentialAuditRequest.Outcome.SAVED && exec.body() != null) {
      Map<String, Object> ok = new LinkedHashMap<>();
      ok.put("version", exec.body().version());
      if (includeBrokerAccountId) {
        // NON-secret authenticated account number, for the operator onboarding confirmation step.
        ok.put("broker_account_id", exec.body().brokerAccountId());
      }
      return ResponseEntity.ok(ok);
    }
    return ResponseEntity.status(exec.callerStatus())
        .body(Map.of("error", "credential_write_failed"));
  }

  /**
   * Forwards the credential to exec. The inbound {@link BrokerCredentialForwardRequest} is sent
   * straight through: its {@code correlation_id} is {@code WRITE_ONLY} so Jackson omits it on the
   * wire (an api-gateway-only concern), and its {@code toString} is redacted so Spring's outbound
   * message-converter TRACE log can never render the api-key/secret (MF-7) — a raw {@code Map}
   * whose {@code toString} echoes the secret would leak it. Status is captured via {@code exchange}
   * so a non-2xx response does not throw before we can map it to the matching audit outcome. A
   * transport-level failure (exec unreachable) maps to the same coarse persist-error outcome as a
   * 5xx.
   */
  private ExecOutcome forwardToExec(String tenant, BrokerCredentialForwardRequest body) {
    try {
      return execRestClient
          .post()
          .uri("/internal/broker-credentials")
          .header("X-Tenant-Id", tenant)
          .body(body)
          .exchange(
              (request, response) -> {
                HttpStatusCode status = response.getStatusCode();
                if (status.is2xxSuccessful()) {
                  BrokerCredentialForwardResponse parsed =
                      response.bodyTo(BrokerCredentialForwardResponse.class);
                  // A 2xx with no body is not a verifiable save — without the version we can
                  // neither
                  // populate a coherent SAVED audit nor return a version to the caller. Treat it as
                  // a
                  // persist error so the audit outcome and the caller response stay consistent.
                  if (parsed == null) {
                    return mapErrorStatus(502);
                  }
                  return new ExecOutcome(
                      BrokerCredentialAuditRequest.Outcome.SAVED, HttpStatus.OK, parsed);
                }
                return mapErrorStatus(status.value());
              },
              false);
    } catch (RuntimeException e) {
      // Transport failure (exec down / timeout). NEVER log the body or the cause message (could
      // wrap inputs) — only the coarse type. Treated as a persist error for audit + caller.
      log.error(
          "broker credential forward to exec failed tenant={} cause={}",
          tenant,
          e.getClass().getName());
      return new ExecOutcome(
          BrokerCredentialAuditRequest.Outcome.REJECTED_PERSIST_ERROR,
          HttpStatus.BAD_GATEWAY,
          null);
    }
  }

  /**
   * Coarse exec-status → (caller status, audit outcome). P6-c deliberately coarsens exec's
   * rejections (no oracle): 422 {@code credential_rejected} → validation; 409 stale-version and 500
   * catch-all → persist error. Anything else (incl. transport) → 502.
   */
  private static ExecOutcome mapErrorStatus(int execStatus) {
    return switch (execStatus) {
      case 422 ->
          new ExecOutcome(
              BrokerCredentialAuditRequest.Outcome.REJECTED_VALIDATION,
              HttpStatus.UNPROCESSABLE_ENTITY,
              null);
      case 409 ->
          new ExecOutcome(
              BrokerCredentialAuditRequest.Outcome.REJECTED_PERSIST_ERROR,
              HttpStatus.CONFLICT,
              null);
      default ->
          new ExecOutcome(
              BrokerCredentialAuditRequest.Outcome.REJECTED_PERSIST_ERROR,
              HttpStatus.BAD_GATEWAY,
              null);
    };
  }

  /**
   * Starts the audit workflow non-blocking (fire-and-forget {@code WorkflowClient.start}). The
   * workflow id embeds the correlation id and the reuse policy is REJECT_DUPLICATE, so a retried
   * write dedups rather than double-auditing. On any failure we log loudly (NO secret), bump a
   * counter, and STILL return the write's success/coarse result — the write already happened in
   * exec; the DB row's updated_by/version is the fallback record (live-hardening defers atomicity).
   */
  private void startAuditWorkflow(
      String tenant, String correlationId, BrokerCredentialAuditRequest auditRequest) {
    try {
      WorkflowOptions opts =
          WorkflowOptions.newBuilder()
              .setTaskQueue(AUDIT_TASK_QUEUE)
              .setWorkflowId(WorkflowIds.brokerCredentialAudit(tenant, correlationId))
              .setWorkflowIdReusePolicy(
                  WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
              .build();
      BrokerCredentialAuditWorkflow stub =
          workflowClient.newWorkflowStub(BrokerCredentialAuditWorkflow.class, opts);
      WorkflowClient.start(stub::record, auditRequest);
    } catch (RuntimeException e) {
      auditStartFailures.increment();
      log.error(
          "broker credential audit-workflow start failed tenant={} correlationId={} cause={}",
          tenant,
          correlationId,
          e.getClass().getName());
    }
  }

  /** The mapped exec outcome: the audit outcome, the coarse caller status, and the parsed body. */
  private record ExecOutcome(
      BrokerCredentialAuditRequest.Outcome outcome,
      HttpStatusCode callerStatus,
      BrokerCredentialForwardResponse body) {}
}
