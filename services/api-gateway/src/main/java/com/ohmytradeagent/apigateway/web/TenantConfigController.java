package com.ohmytradeagent.apigateway.web;

import com.ohmytradeagent.contract.TenantConfigUpdateRequest;
import com.ohmytradeagent.contract.TenantConfigUpdateResult;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.workflows.TenantConfigUpdateWorkflow;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowOptions;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * account-loss-cap-db (Phase 3) tenant account-cap WRITE forward endpoint. {@code POST
 * /tenant-config} takes the FULL desired account-cap state from the dashboard server, starts a
 * short-lived {@link TenantConfigUpdateWorkflow} on the orchestrator-core queue, synchronously
 * reads its {@link TenantConfigUpdateResult}, and maps the coarse outcome to an HTTP status.
 *
 * <p><b>Dark by construction.</b> Gated on {@code tenant.config.write.enabled=true}; with the flag
 * unset (repo default / homelab) the bean does not exist → the route 404s. NO repo manifest sets it
 * true.
 *
 * <p><b>Tighten-only.</b> The orchestrator's {@code TenantConfigWriter} hard-blocks any raise,
 * remove, add-where-none, or below-floor account-cap change (→ {@code REJECTED_TIGHTEN_ONLY} 403 /
 * {@code REJECTED_BELOW_FLOOR} 422). The activity NEVER coarsens a rejection into a success; this
 * controller NEVER reports an unknown disposition as success — a workflow failure / timeout maps to
 * 503, not 200.
 *
 * <p><b>Auth + tenant trust.</b> {@link com.ohmytradeagent.apigateway.security.ServiceTokenFilter}
 * authenticates the caller before this runs; the controller then requires a validated {@code
 * X-Tenant-Id} (no dev fallback) and asserts it equals {@code body.tenant_id} — a mismatch is a
 * cross-tenant write attempt, rejected 403 with no detail.
 */
@RestController
@RequestMapping("/tenant-config")
@ConditionalOnProperty(name = "tenant.config.write.enabled", havingValue = "true")
public class TenantConfigController {

  private static final Logger log = LoggerFactory.getLogger(TenantConfigController.class);
  private static final String TASK_QUEUE = "orchestrator-core";
  private static final String ACTOR = "api-gateway:/tenant-config";
  // Server-side run-timeout so the typed blocking stub.update(...) cannot pin the Spring MVC
  // request
  // thread indefinitely when the orchestrator-core queue has no live poller or the workflow wedges.
  // On timeout the workflow fails → WorkflowException (caught below) → 503 (write disposition
  // unknown — never reported as success).
  private static final Duration WORKFLOW_RUN_TIMEOUT = Duration.ofSeconds(30);

  private final WorkflowClient workflowClient;
  private final TenantContext ctx;

  public TenantConfigController(WorkflowClient workflowClient, TenantContext ctx) {
    this.workflowClient = workflowClient;
    this.ctx = ctx;
  }

  @PostMapping
  public ResponseEntity<Map<String, Object>> write(
      HttpServletRequest req, @RequestBody TenantConfigWriteRequest body) {

    // (a) strict tenant — 400 if absent/blank/malformed (no dev fallback on this route).
    String tenant = ctx.requiredTenantId(req);

    // (b) cross-tenant guard — coarse 403, no detail (no oracle).
    if (body == null || !tenant.equals(body.tenantId())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    String correlationId =
        (body.correlationId() != null && !body.correlationId().isBlank())
            ? body.correlationId()
            : UUID.randomUUID().toString();

    // (c) build the workflow input (schema_version pinned to the build's contract version).
    TenantConfigUpdateRequest request = new TenantConfigUpdateRequest();
    request.setSchemaVersion(1L);
    request.setTenantId(tenant);
    request.setAccountDailyLossThreshold(body.accountDailyLossThreshold());
    request.setAccountDailyLossPct(body.accountDailyLossPct());
    request.setExpectedVersion(body.expectedVersion());
    request.setActor(ACTOR);
    request.setCorrelationId(correlationId);

    // (d) start-and-getResult via a typed BLOCKING stub. REJECT_DUPLICATE so a retried write dedups
    // on the correlation-keyed workflow id rather than re-running the non-idempotent CAS.
    WorkflowOptions opts =
        WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setWorkflowId(WorkflowIds.tenantConfigUpdate(tenant, correlationId))
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .setWorkflowRunTimeout(WORKFLOW_RUN_TIMEOUT)
            .build();
    TenantConfigUpdateWorkflow stub =
        workflowClient.newWorkflowStub(TenantConfigUpdateWorkflow.class, opts);

    TenantConfigUpdateResult result;
    try {
      result = stub.update(request);
    } catch (WorkflowException e) {
      // Workflow failed / timed out: write disposition is UNKNOWN — NEVER report it as a success.
      // 503 so the caller may safely re-read the version and retry.
      log.warn(
          "tenant-config write workflow failed tenant={} cause={}", tenant, e.getClass().getName());
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    }

    return mapOutcome(result);
  }

  /**
   * Coarse outcome → HTTP. UPDATED 200 (+new_version); REJECTED_STALE_VERSION 409;
   * REJECTED_TIGHTEN_ONLY 403; REJECTED_BELOW_FLOOR 422; REJECTED_INVALID 400; NOT_FOUND 404;
   * anything else (incl. a null outcome) 503 — an unknown disposition is NEVER reported as success.
   */
  private static ResponseEntity<Map<String, Object>> mapOutcome(TenantConfigUpdateResult result) {
    TenantConfigUpdateResult.Outcome outcome = result == null ? null : result.getOutcome();
    if (outcome == null) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    }
    return switch (outcome) {
      case UPDATED -> {
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("status", outcome.value());
        ok.put("new_version", result.getNewVersion());
        yield ResponseEntity.ok(ok);
      }
      case REJECTED_STALE_VERSION ->
          ResponseEntity.status(HttpStatus.CONFLICT).body(statusBody(outcome));
      case REJECTED_TIGHTEN_ONLY ->
          ResponseEntity.status(HttpStatus.FORBIDDEN).body(statusBody(outcome));
      case REJECTED_BELOW_FLOOR ->
          ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(statusBody(outcome));
      case REJECTED_INVALID ->
          ResponseEntity.status(HttpStatus.BAD_REQUEST).body(statusBody(outcome));
      case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(statusBody(outcome));
    };
  }

  private static Map<String, Object> statusBody(TenantConfigUpdateResult.Outcome outcome) {
    return Map.of("status", outcome.value());
  }
}
