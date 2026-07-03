package com.ohmytradeagent.apigateway.web;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.StrategyConfigUpdateRequest;
import com.ohmytradeagent.contract.StrategyConfigUpdateResult;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowOptions;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
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
 * A1 operator-scoped ARM route. {@code POST /admin/tenants/{tenant}/strategies/{strategy}/enable}
 * arms a tenant's strategy ({@code enabled → true}) from the operator onboard wizard — a
 * cross-tenant action the tenant-scoped {@link StrategyConfigController} cannot serve (it binds
 * {@code X-Tenant-Id} and rejects a cross-tenant write).
 *
 * <p><b>Bypass-proof arm-guard (the load-bearing A1 enforcement).</b> It reads the stored config,
 * asks exec (via {@link VerifiedAccountGuard}, keyed off the STORED {@code broker_target}) whether
 * a verified broker account exists, and ONLY then flips {@code enabled=true} and starts the
 * existing {@link com.ohmytradeagent.orchestrator.workflows.StrategyConfigUpdateWorkflow}. No
 * verified account → 422, workflow NOT started. exec fault / unsupported (live) target → 503 / 422;
 * neither arms. {@code enabled} stays a SAFE field in the orchestrator writer — this is a gateway
 * pre-check, not a writer field-class change.
 *
 * <p><b>Dark by construction.</b> Gated on {@code operator.strategy-enable.enabled=true}; with the
 * flag unset (repo default / homelab) the bean does not exist → the route 404s. That flag is also
 * in {@link com.ohmytradeagent.apigateway.security.ServiceTokenFilter}'s expression, so enabling
 * this route also brings up the bearer gate (no unauthenticated {@code /admin/tenants/} route).
 *
 * <p><b>Operator-scoped auth.</b> Mirrors {@link CreateTenantController}/{@link
 * ActivationController}: the {@link ServiceTokenFilter} bearer-gates {@code /admin/tenants/}, then
 * this controller requires an ALLOWLISTED {@code X-Operator-Id} (400 if absent/malformed, 403 if
 * not allowlisted). The (tenant, strategy) come from the path.
 */
@RestController
@RequestMapping("/admin/tenants")
@ConditionalOnProperty(name = "operator.strategy-enable.enabled", havingValue = "true")
public class OperatorStrategyEnableController {

  private static final Logger log = LoggerFactory.getLogger(OperatorStrategyEnableController.class);
  private static final String TASK_QUEUE = "orchestrator-core";
  private static final String ACTOR = "api-gateway:/admin/strategies/enable";
  // Server-side run-timeout — same discipline as StrategyConfigController: a stub call cannot pin
  // the request thread if orchestrator-core has no live poller / the workflow wedges (→ 503).
  private static final Duration WORKFLOW_RUN_TIMEOUT = Duration.ofSeconds(30);

  private final WorkflowClient workflowClient;
  private final TenantContext ctx;
  private final StrategyConfigReader reader;
  private final VerifiedAccountGuard guard;

  public OperatorStrategyEnableController(
      WorkflowClient workflowClient,
      TenantContext ctx,
      StrategyConfigReader reader,
      VerifiedAccountGuard guard) {
    this.workflowClient = workflowClient;
    this.ctx = ctx;
    this.reader = reader;
    this.guard = guard;
  }

  @PostMapping("/{tenant}/strategies/{strategy}/enable")
  public ResponseEntity<Map<String, Object>> enable(
      HttpServletRequest req,
      @PathVariable("tenant") String tenant,
      @PathVariable("strategy") String strategy,
      @RequestBody(required = false) StrategyEnableRequest body) {

    String operator =
        ctx.requireAllowlistedOperator(req); // 400 if absent/malformed, 403 if not allowlisted

    // (a) read the stored config + optimistic-concurrency version. No row → 404 (nothing to arm).
    Optional<StrategyConfigReader.Stored> storedOpt = reader.read(tenant, strategy);
    if (storedOpt.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("status", "NOT_FOUND"));
    }
    StrategyConfigReader.Stored stored = storedOpt.get();
    StrategyConfig config = stored.config();

    // (b) arm-guard: verified broker account must exist for the STORED broker_target (C5/C7).
    String brokerTarget =
        config.getBrokerTarget() == null ? null : config.getBrokerTarget().value();
    VerifiedAccountGuard.Decision decision = guard.evaluate(tenant, brokerTarget);
    switch (decision) {
      case ALLOW -> {
        /* proceed */
      }
      case REJECT_UNVERIFIED ->
          throw new ResponseStatusException(
              HttpStatus.UNPROCESSABLE_ENTITY, "REJECTED_UNVERIFIED_ACCOUNT");
      case REJECT_UNSUPPORTED_TARGET ->
          throw new ResponseStatusException(
              HttpStatus.UNPROCESSABLE_ENTITY, "REJECTED_UNSUPPORTED_TARGET");
      case FAULT ->
          // exec unreachable / malformed — arming disposition unknown, NEVER a silent arm.
          throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    }

    // (c) flip enabled=true on the stored config and CAS via the existing update workflow.
    config.setEnabled(true);

    String correlationId =
        (body != null && body.correlationId() != null && !body.correlationId().isBlank())
            ? body.correlationId()
            : UUID.randomUUID().toString();

    StrategyConfigUpdateRequest request = new StrategyConfigUpdateRequest();
    request.setSchemaVersion(1L);
    request.setTenantId(tenant);
    request.setStrategyId(strategy);
    request.setConfig(config);
    request.setExpectedVersion(stored.version());
    request.setActor(ACTOR + ":" + operator);
    request.setCorrelationId(correlationId);

    WorkflowOptions opts =
        WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setWorkflowId(WorkflowIds.strategyConfigUpdate(tenant, strategy, correlationId))
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .setWorkflowRunTimeout(WORKFLOW_RUN_TIMEOUT)
            .build();
    com.ohmytradeagent.orchestrator.workflows.StrategyConfigUpdateWorkflow stub =
        workflowClient.newWorkflowStub(
            com.ohmytradeagent.orchestrator.workflows.StrategyConfigUpdateWorkflow.class, opts);

    StrategyConfigUpdateResult result;
    try {
      result = stub.update(request);
    } catch (WorkflowException e) {
      log.warn(
          "operator enable workflow failed tenant={} strategy={} cause={}",
          tenant,
          strategy,
          e.getClass().getName());
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    }
    return mapOutcome(result);
  }

  /**
   * Coarse outcome → HTTP, mirroring {@link StrategyConfigController}: UPDATED 200 (+new_version);
   * STALE 409; DANGEROUS 403; INVALID 400; NOT_FOUND 404; anything else / null → 503 (unknown
   * disposition, NEVER reported as success).
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
          ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", outcome.value()));
      case REJECTED_DANGEROUS ->
          ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("status", outcome.value()));
      case REJECTED_INVALID ->
          ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("status", outcome.value()));
      case NOT_FOUND ->
          ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("status", outcome.value()));
      default -> throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    };
  }
}
