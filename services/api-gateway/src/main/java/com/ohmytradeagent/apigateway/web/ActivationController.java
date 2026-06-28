package com.ohmytradeagent.apigateway.web;

import com.ohmytradeagent.contract.LiveActivationRequest;
import com.ohmytradeagent.contract.LiveActivationResult;
import com.ohmytradeagent.contract.LiveDeactivationRequest;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.workflows.LiveActivationWorkflow;
import com.ohmytradeagent.orchestrator.workflows.LiveDeactivationWorkflow;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phase F (operator-account-onboarding) one-click live activation / deactivation endpoint.
 *
 * <ul>
 *   <li>{@code POST /admin/tenants/{tenant}/strategies/{strategy}/activate-live}
 *   <li>{@code POST /admin/tenants/{tenant}/strategies/{strategy}/deactivate-live}
 * </ul>
 *
 * <p>Resolves the authenticated operator ({@code X-Operator-Id}; 400 if absent), starts the
 * short-lived {@link LiveActivationWorkflow}/{@link LiveDeactivationWorkflow} on orchestrator-core
 * (the StrategyConfigController start-and-getResult pattern: typed blocking stub, 30s run-timeout,
 * REJECT_DUPLICATE keyed by a correlation id including tenant/strategy), and maps the coarse
 * outcome to an HTTP status: {@code ACTIVATED}/{@code DEACTIVATED} → 200; any {@code REJECTED_*} →
 * 422 with the reason; a workflow failure/timeout (infra) → 503.
 *
 * <p><b>Dark by construction.</b> Gated on {@code operator.activation.enabled=true}; with the flag
 * unset (repo default / homelab) the bean does not exist → the route 404s. NO repo manifest sets it
 * true.
 *
 * <p><b>Auth.</b> {@link com.ohmytradeagent.apigateway.security.ServiceTokenFilter} bearer-gates
 * the {@code /admin/tenants/} route before this runs (401 on a missing/bad token), then this
 * controller requires the {@code X-Operator-Id} header (400 if absent). The tenant/strategy come
 * from the path.
 */
@RestController
@RequestMapping("/admin/tenants")
@ConditionalOnProperty(name = "operator.activation.enabled", havingValue = "true")
public class ActivationController {

  private static final Logger log = LoggerFactory.getLogger(ActivationController.class);
  private static final String TASK_QUEUE = "orchestrator-core";
  // Server-side run-timeout so the typed blocking stub call cannot pin the Spring MVC request
  // thread indefinitely when orchestrator-core has no live poller (worker down / rolling) or the
  // workflow wedges. On timeout → WorkflowException (caught below) → 503.
  private static final Duration WORKFLOW_RUN_TIMEOUT = Duration.ofSeconds(30);

  private final WorkflowClient workflowClient;
  private final TenantContext ctx;

  public ActivationController(WorkflowClient workflowClient, TenantContext ctx) {
    this.workflowClient = workflowClient;
    this.ctx = ctx;
  }

  @PostMapping("/{tenant}/strategies/{strategy}/activate-live")
  public ResponseEntity<Map<String, Object>> activate(
      HttpServletRequest req,
      @PathVariable("tenant") String tenant,
      @PathVariable("strategy") String strategy) {

    String operator = ctx.operatorId(req); // 400 if X-Operator-Id absent
    String correlationId = UUID.randomUUID().toString();

    LiveActivationRequest request = new LiveActivationRequest();
    request.setSchemaVersion(1L);
    request.setTenantId(tenant);
    request.setStrategyId(strategy);
    // Non-null placeholder only; the workflow overwrites broker_target from the stored config.
    request.setBrokerTarget(LiveActivationRequest.BrokerTarget.LIVE);
    request.setOperatorId(operator);

    WorkflowOptions opts = options(tenant, strategy, correlationId);
    LiveActivationWorkflow stub =
        workflowClient.newWorkflowStub(LiveActivationWorkflow.class, opts);

    LiveActivationResult result;
    try {
      result = stub.activateLive(request);
    } catch (WorkflowException e) {
      log.warn(
          "activate-live workflow failed tenant={} strategy={} cause={}",
          tenant,
          strategy,
          e.getClass().getName());
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    }
    return mapOutcome(result);
  }

  @PostMapping("/{tenant}/strategies/{strategy}/deactivate-live")
  public ResponseEntity<Map<String, Object>> deactivate(
      HttpServletRequest req,
      @PathVariable("tenant") String tenant,
      @PathVariable("strategy") String strategy) {

    String operator = ctx.operatorId(req); // 400 if X-Operator-Id absent
    String correlationId = UUID.randomUUID().toString();

    LiveDeactivationRequest request = new LiveDeactivationRequest();
    request.setSchemaVersion(1L);
    request.setTenantId(tenant);
    request.setStrategyId(strategy);
    // Non-null placeholder only; the workflow overwrites broker_target from the stored config.
    request.setBrokerTarget(LiveDeactivationRequest.BrokerTarget.LIVE);
    request.setOperatorId(operator);

    WorkflowOptions opts = options(tenant, strategy, correlationId);
    LiveDeactivationWorkflow stub =
        workflowClient.newWorkflowStub(LiveDeactivationWorkflow.class, opts);

    LiveActivationResult result;
    try {
      result = stub.deactivateLive(request);
    } catch (WorkflowException e) {
      log.warn(
          "deactivate-live workflow failed tenant={} strategy={} cause={}",
          tenant,
          strategy,
          e.getClass().getName());
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    }
    return mapOutcome(result);
  }

  private WorkflowOptions options(String tenant, String strategy, String correlationId) {
    return WorkflowOptions.newBuilder()
        .setTaskQueue(TASK_QUEUE)
        .setWorkflowId(WorkflowIds.liveActivation(tenant, strategy, correlationId))
        .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
        .setWorkflowRunTimeout(WORKFLOW_RUN_TIMEOUT)
        .build();
  }

  /**
   * Coarse outcome → HTTP. ACTIVATED/DEACTIVATED → 200 (+ expected_account_id when present); any
   * REJECTED_* → 422 with the reason; a null outcome → 503 (disposition unknown, NEVER reported as
   * success).
   */
  private static ResponseEntity<Map<String, Object>> mapOutcome(LiveActivationResult result) {
    LiveActivationResult.Outcome outcome = result == null ? null : result.getOutcome();
    if (outcome == null) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", outcome.value());
    return switch (outcome) {
      case ACTIVATED, DEACTIVATED -> {
        if (result.getExpectedAccountId() != null) {
          body.put("expected_account_id", result.getExpectedAccountId());
        }
        yield ResponseEntity.ok(body);
      }
      default -> {
        if (result.getReason() != null) {
          body.put("reason", result.getReason());
        }
        yield ResponseEntity.unprocessableEntity().body(body);
      }
    };
  }
}
