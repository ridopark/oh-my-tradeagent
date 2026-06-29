package com.ohmytradeagent.apigateway.web;

import com.ohmytradeagent.contract.StrategyConfigCreateRequest;
import com.ohmytradeagent.contract.StrategyConfigCreateResult;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.workflows.StrategyConfigCreateWorkflow;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phase I-1b (operator-account-onboarding) create-tenant endpoint. {@code POST
 * /admin/tenants/{tenant}/strategies/{strategy}} — "create tenant" = INSERT the first {@code
 * strategy_config} row for a new (tenant, strategy) at version 1 (Phase-0 DECISION: NO separate
 * tenants table). Starts the short-lived {@link StrategyConfigCreateWorkflow} on orchestrator-core
 * (the StrategyConfigController start-and-getResult pattern), synchronously reads its {@link
 * StrategyConfigCreateResult}, and maps the coarse outcome to an HTTP status: {@code CREATED} → 200
 * (+created_version); {@code ALREADY_EXISTS} → 409; {@code REJECTED_INVALID} → 400; a workflow
 * failure/timeout (infra) → 503.
 *
 * <p><b>Dark by construction.</b> Gated on {@code operator.tenant-create.enabled=true}; with the
 * flag unset (repo default / homelab) the bean does not exist → the route 404s. NO repo manifest
 * sets it true.
 *
 * <p><b>Operator-scoped auth.</b> Unlike {@link StrategyConfigController} (which binds X-Tenant-Id
 * and rejects a cross-tenant write), create is inherently cross-tenant — the operator creates a
 * tenant they do not belong to. So it mirrors {@link ActivationController}: {@link
 * com.ohmytradeagent.apigateway.security.ServiceTokenFilter} bearer-gates the {@code
 * /admin/tenants/} route, then this controller requires {@code X-Operator-Id} (400 if absent). The
 * (tenant, strategy) come from the path; the writer rejects a config whose own ids drift from them.
 */
@RestController
@RequestMapping("/admin/tenants")
@ConditionalOnProperty(name = "operator.tenant-create.enabled", havingValue = "true")
public class CreateTenantController {

  private static final Logger log = LoggerFactory.getLogger(CreateTenantController.class);
  private static final String TASK_QUEUE = "orchestrator-core";
  // Server-side run-timeout so the typed blocking stub call cannot pin the Spring MVC request
  // thread
  // indefinitely when orchestrator-core has no live poller (worker down / rolling) or the workflow
  // wedges. On timeout → WorkflowException (caught below) → 503 (create disposition unknown).
  private static final Duration WORKFLOW_RUN_TIMEOUT = Duration.ofSeconds(30);

  private final WorkflowClient workflowClient;
  private final TenantContext ctx;

  public CreateTenantController(WorkflowClient workflowClient, TenantContext ctx) {
    this.workflowClient = workflowClient;
    this.ctx = ctx;
  }

  @PostMapping("/{tenant}/strategies/{strategy}")
  public ResponseEntity<Map<String, Object>> create(
      HttpServletRequest req,
      @PathVariable("tenant") String tenant,
      @PathVariable("strategy") String strategy,
      @RequestBody TenantCreateRequest body) {

    String operator = ctx.operatorId(req); // 400 if X-Operator-Id absent
    String correlationId =
        (body != null && body.correlationId() != null && !body.correlationId().isBlank())
            ? body.correlationId()
            : UUID.randomUUID().toString();

    StrategyConfigCreateRequest request = new StrategyConfigCreateRequest();
    request.setSchemaVersion(1L);
    request.setTenantId(tenant);
    request.setStrategyId(strategy);
    request.setConfig(body == null ? null : body.config());
    request.setOperatorId(operator);
    request.setCorrelationId(correlationId);

    WorkflowOptions opts =
        WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setWorkflowId(WorkflowIds.strategyConfigCreate(tenant, strategy, correlationId))
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .setWorkflowRunTimeout(WORKFLOW_RUN_TIMEOUT)
            .build();
    StrategyConfigCreateWorkflow stub =
        workflowClient.newWorkflowStub(StrategyConfigCreateWorkflow.class, opts);

    StrategyConfigCreateResult result;
    try {
      result = stub.create(request);
    } catch (WorkflowException e) {
      log.warn(
          "create-tenant workflow failed tenant={} strategy={} cause={}",
          tenant,
          strategy,
          e.getClass().getName());
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    }
    return mapOutcome(result);
  }

  /**
   * Coarse outcome → HTTP. CREATED → 200 (+created_version); ALREADY_EXISTS → 409; REJECTED_INVALID
   * → 400; a null outcome → 503 (disposition unknown, NEVER reported as success).
   */
  private static ResponseEntity<Map<String, Object>> mapOutcome(StrategyConfigCreateResult result) {
    StrategyConfigCreateResult.Outcome outcome = result == null ? null : result.getOutcome();
    if (outcome == null) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", outcome.value());
    return switch (outcome) {
      case CREATED -> {
        body.put("created_version", result.getCreatedVersion());
        yield ResponseEntity.ok(body);
      }
      case ALREADY_EXISTS -> ResponseEntity.status(HttpStatus.CONFLICT).body(body);
      case REJECTED_INVALID -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    };
  }
}
