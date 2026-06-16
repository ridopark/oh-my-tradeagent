package com.ohmytradeagent.apigateway.web;

import com.ohmytradeagent.contract.StrategyConfigUpdateRequest;
import com.ohmytradeagent.contract.StrategyConfigUpdateResult;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.workflows.StrategyConfigUpdateWorkflow;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowOptions;
import jakarta.servlet.http.HttpServletRequest;
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
 * UI-P3-b strategy-config WRITE forward endpoint. {@code POST /strategy-config} takes a proposed
 * {@link com.ohmytradeagent.contract.StrategyConfig} from the dashboard server, starts a
 * short-lived {@link StrategyConfigUpdateWorkflow} on the orchestrator-core queue, synchronously
 * reads its {@link StrategyConfigUpdateResult}, and maps the coarse outcome to an HTTP status.
 *
 * <p><b>Dark by construction.</b> Gated on {@code strategy.config.write.enabled=true}; with the
 * flag unset (repo default / homelab) the bean does not exist → the route 404s. NO repo manifest
 * sets it true.
 *
 * <p><b>Reduce-or-hold-risk only.</b> The orchestrator's {@code StrategyConfigWriter} hard-blocks
 * any risk-increasing, identity-drifting, or live-routing field change (→ {@code
 * REJECTED_DANGEROUS} → 403). The activity NEVER coarsens a dangerous rejection into a success;
 * this controller NEVER reports an unknown disposition as success — a workflow failure / timeout
 * (the activity let a corrupt-row {@code IllegalStateException} propagate) maps to 503, not 200.
 *
 * <p><b>Auth + tenant trust.</b> {@link com.ohmytradeagent.apigateway.security.ServiceTokenFilter}
 * authenticates the caller before this runs; the controller then requires a validated {@code
 * X-Tenant-Id} (no dev fallback) and asserts it equals {@code body.tenant_id} — a mismatch is a
 * cross-tenant write attempt, rejected 403 with no detail. The {@code strategy_id} is trusted
 * as-is: the writer's IDENTITY / NOT_FOUND gates are authoritative (a wrong/absent strategy yields
 * NOT_FOUND, never a write).
 */
@RestController
@RequestMapping("/strategy-config")
@ConditionalOnProperty(name = "strategy.config.write.enabled", havingValue = "true")
public class StrategyConfigController {

  private static final Logger log = LoggerFactory.getLogger(StrategyConfigController.class);
  private static final String TASK_QUEUE = "orchestrator-core";
  private static final String ACTOR = "api-gateway:/strategy-config";

  private final WorkflowClient workflowClient;
  private final TenantContext ctx;

  public StrategyConfigController(WorkflowClient workflowClient, TenantContext ctx) {
    this.workflowClient = workflowClient;
    this.ctx = ctx;
  }

  @PostMapping
  public ResponseEntity<Map<String, Object>> write(
      HttpServletRequest req, @RequestBody StrategyConfigWriteRequest body) {

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
    StrategyConfigUpdateRequest request = new StrategyConfigUpdateRequest();
    request.setSchemaVersion(1L);
    request.setTenantId(tenant);
    request.setStrategyId(body.strategyId());
    request.setConfig(body.config());
    request.setExpectedVersion(body.expectedVersion());
    request.setActor(ACTOR);
    request.setCorrelationId(correlationId);

    // (d) start-and-getResult via a typed BLOCKING stub. REJECT_DUPLICATE so a retried write dedups
    // on the correlation-keyed workflow id rather than re-running the non-idempotent CAS.
    WorkflowOptions opts =
        WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setWorkflowId(
                WorkflowIds.strategyConfigUpdate(tenant, body.strategyId(), correlationId))
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .build();
    StrategyConfigUpdateWorkflow stub =
        workflowClient.newWorkflowStub(StrategyConfigUpdateWorkflow.class, opts);

    StrategyConfigUpdateResult result;
    try {
      result = stub.update(request);
    } catch (WorkflowException e) {
      // Workflow failed / timed out: the activity let a corrupt-row IllegalStateException propagate
      // (or the workflow could not complete). Write disposition is UNKNOWN — NEVER report it as a
      // success. 503 so the caller may safely re-read the version and retry.
      log.warn(
          "strategy-config write workflow failed tenant={} strategy={} cause={}",
          tenant,
          body.strategyId(),
          e.getClass().getName());
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    }

    return mapOutcome(result);
  }

  /**
   * Coarse outcome → HTTP. UPDATED 200 (+new_version); REJECTED_STALE_VERSION 409;
   * REJECTED_DANGEROUS 403; REJECTED_INVALID 400; NOT_FOUND 404; anything else (incl. the
   * never-emitted REJECTED_PERSIST_ERROR / a null outcome) 503 — an unknown disposition is NEVER
   * reported as success.
   */
  private static ResponseEntity<Map<String, Object>> mapOutcome(StrategyConfigUpdateResult result) {
    StrategyConfigUpdateResult.Outcome outcome = result == null ? null : result.getOutcome();
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
      case REJECTED_DANGEROUS ->
          ResponseEntity.status(HttpStatus.FORBIDDEN).body(statusBody(outcome));
      case REJECTED_INVALID ->
          ResponseEntity.status(HttpStatus.BAD_REQUEST).body(statusBody(outcome));
      case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(statusBody(outcome));
      default -> throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    };
  }

  private static Map<String, Object> statusBody(StrategyConfigUpdateResult.Outcome outcome) {
    return Map.of("status", outcome.value());
  }
}
