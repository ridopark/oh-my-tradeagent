package com.ohmytradeagent.apigateway.web;

import com.ohmytradeagent.contract.BrokerCredentialAuditRequest;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.workflows.BrokerCredentialAuditWorkflow;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * UI-P2-a credential-write forward endpoint. {@code POST /broker-credentials} takes a
 * tenant-entered broker key from the dashboard server, forwards it (secret in the HTTP body only)
 * to exec's {@code POST /internal/broker-credentials}, maps the exec outcome to a coarse caller
 * result, and fires a metadata-only {@link BrokerCredentialAuditWorkflow} on the orchestrator-core
 * queue.
 *
 * <p><b>Dark by construction.</b> Gated on {@code broker.credentials.write.enabled=true}; with the
 * flag unset (repo default / homelab) the bean does not exist → the route 404s.
 *
 * <p><b>Auth + tenant trust.</b> {@link com.ohmytradeagent.apigateway.security.ServiceTokenFilter}
 * authenticates the caller (shared bearer) before this runs; the controller then requires a
 * validated {@code X-Tenant-Id} (no dev fallback) and asserts it equals {@code body.tenant_id} — a
 * mismatch is a cross-tenant write attempt, rejected 403 with no detail.
 *
 * <p><b>MF-7.</b> The request body, api-key, and secret are NEVER logged (the request record's
 * {@code toString} is redacted), NEVER echoed in the response, and NEVER placed on the Temporal
 * payload (the audit request carries only non-secret metadata). The secret rides ONLY the
 * api-gateway→exec HTTP body.
 *
 * <p><b>Rate-limit (UI-P2-a).</b> An in-process per-tenant fixed-window counter caps writes (the
 * exec {@code /v2/account} probe is reachable through this path; bounding attempts prevents it
 * being used as a key-testing oracle or a cost/DoS lever). Over cap → 429, no forward, no audit.
 */
@RestController
@RequestMapping("/broker-credentials")
@ConditionalOnProperty(name = "broker.credentials.write.enabled", havingValue = "true")
public class BrokerCredentialController {

  private static final Logger log = LoggerFactory.getLogger(BrokerCredentialController.class);
  private static final String AUDIT_TASK_QUEUE = "orchestrator-core";
  private static final String ACTOR = "api-gateway:/broker-credentials";

  private final RestClient execRestClient;
  private final WorkflowClient workflowClient;
  private final TenantContext ctx;
  private final Clock clock;
  private final Counter auditStartFailures;
  private final int ratePerMinute;

  // Per-tenant fixed-window counter: maps tenant -> current minute-window state. ConcurrentHashMap
  // keeps it lock-light; the window resets when the wall-clock minute bucket advances. The stale
  // Window is replaced in place each minute (no per-minute accumulation), but a tenant key is never
  // evicted — bounded by the validated ([A-Za-z0-9_-]+) distinct-tenant set, negligible in
  // practice.
  // The UI-P2-c limiter replaces this stopgap wholesale (size-capped/TTL-evicting), so no eviction
  // is added here.
  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

  public BrokerCredentialController(
      RestClient execRestClient,
      WorkflowClient workflowClient,
      TenantContext ctx,
      Clock clock,
      MeterRegistry meterRegistry,
      @Value("${broker.credentials.write.rate-per-minute:10}") int ratePerMinute) {
    this.execRestClient = execRestClient;
    this.workflowClient = workflowClient;
    this.ctx = ctx;
    this.clock = clock;
    this.ratePerMinute = ratePerMinute;
    this.auditStartFailures =
        Counter.builder("broker_credential_audit_start_failures")
            .description("count of credential-write audit-workflow starts that failed to dispatch")
            .register(meterRegistry);
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

    // (c) rate-limit cap — over cap → 429, no forward, no audit.
    if (!allow(tenant)) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
    }

    // (d) forward to exec, capturing the status without letting a non-2xx throw before mapping.
    ExecOutcome exec = forwardToExec(tenant, body);

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
    auditRequest.setActor(ACTOR);
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

  /**
   * Per-tenant fixed-window rate limiter. Returns true if the write is within the per-minute cap.
   * The window key is the current epoch-minute; when it advances the count resets. {@code compute}
   * serializes the read-bump per tenant.
   */
  private boolean allow(String tenant) {
    long minute = clock.millis() / 60_000L;
    Window w =
        windows.compute(
            tenant,
            (k, existing) -> {
              if (existing == null || existing.minute != minute) {
                return new Window(minute);
              }
              return existing;
            });
    return w.count.incrementAndGet() <= ratePerMinute;
  }

  /** Mutable per-tenant window: the epoch-minute bucket and the count of writes within it. */
  private static final class Window {
    private final long minute;
    private final AtomicInteger count = new AtomicInteger(0);

    private Window(long minute) {
      this.minute = minute;
    }
  }

  /** The mapped exec outcome: the audit outcome, the coarse caller status, and the parsed body. */
  private record ExecOutcome(
      BrokerCredentialAuditRequest.Outcome outcome,
      HttpStatusCode callerStatus,
      BrokerCredentialForwardResponse body) {}
}
