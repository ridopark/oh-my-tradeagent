package com.ohmytradeagent.apigateway.web;

import com.ohmytradeagent.contract.AdoptionResult;
import com.ohmytradeagent.contract.AdoptionWorkflowInput;
import com.ohmytradeagent.contract.ForceCloseRequest;
import com.ohmytradeagent.contract.ForceCloseResult;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Position-lifecycle endpoints.
 *
 * <ul>
 *   <li>{@code GET /positions} — list running PositionWorkflows for the caller's (tenant, strategy)
 *       via Advanced Visibility {@code listWorkflowExecutions} filtered on the {@code
 *       TenantStrategy} Search Attribute.
 *   <li>{@code POST /positions/force-close} — Update {@code force_close} with WaitPolicy=Accepted;
 *       returns the workflow's {@link ForceCloseResult} payload directly so the operator can
 *       correlate the eventual fill via {@code position_state} polling.
 *   <li>{@code POST /positions/adopt} — operator-triggered orphan adoption (issue #285). Body
 *       {@code {"occ": "<compact or padded OCC>"}}; tenant/strategy from {@code X-Tenant-Id}/{@code
 *       X-Strategy-Id} (defaults dev/copytrade-v1); operator auth via {@code X-Operator-Id} (same
 *       single-operator pattern as force-close). Starts the short-lived {@code AdoptionWorkflow} on
 *       the orchestrator task queue and returns its {@link AdoptionResult} synchronously.
 * </ul>
 */
@RestController
@RequestMapping("/positions")
public class PositionsController {

  private static final String POSITION_WORKFLOW_TYPE = "PositionWorkflow";
  private static final String ADOPTION_WORKFLOW_TYPE = "AdoptionWorkflow";

  private final WorkflowClient client;
  private final TenantContext ctx;
  private final String orchestratorTaskQueue;

  public PositionsController(
      WorkflowClient client,
      TenantContext ctx,
      @Value("${temporal.orchestrator-task-queue:orchestrator-core}")
          String orchestratorTaskQueue) {
    this.client = client;
    this.ctx = ctx;
    this.orchestratorTaskQueue = orchestratorTaskQueue;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> list(HttpServletRequest req) {
    String tenant = ctx.tenantId(req);
    String strategy = ctx.strategyId(req);
    String query =
        String.format(
            "TenantStrategy = '%s' AND WorkflowType = '%s' AND ExecutionStatus = '%s'",
            WorkflowIds.escapeForVisibilityQuery(WorkflowIds.tenantStrategy(tenant, strategy)),
            POSITION_WORKFLOW_TYPE,
            WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING.name());
    List<Map<String, Object>> rows =
        client
            .listExecutions(query)
            .map(PositionsController::summarize)
            .collect(Collectors.toList());
    return ResponseEntity.ok(
        Map.of("tenant_id", tenant, "strategy_id", strategy, "count", rows.size(), "items", rows));
  }

  @PostMapping("/force-close")
  public ResponseEntity<ForceCloseResult> forceClose(
      HttpServletRequest req, @RequestBody ForceClosePayload body) {
    if (body.workflowId() == null || body.workflowId().isBlank()) {
      throw new IllegalArgumentException("workflow_id is required");
    }
    String tenant = ctx.tenantId(req);
    String strategy = ctx.strategyId(req);
    String operator = ctx.operatorId(req);

    String requiredPrefix = WorkflowIds.tenantStrategy(tenant, strategy) + "/";
    if (!body.workflowId().startsWith(requiredPrefix)) {
      throw new IllegalArgumentException(
          "workflow_id does not match caller tenant/strategy: " + body.workflowId());
    }

    ForceCloseRequest fr = new ForceCloseRequest();
    fr.setSchemaVersion(1L);
    fr.setOperatorId(operator);
    fr.setReason(body.reason() == null ? "operator_force_close" : body.reason());

    WorkflowStub stub = client.newUntypedWorkflowStub(body.workflowId());
    ForceCloseResult result = stub.update("force_close", ForceCloseResult.class, fr);
    HttpStatus status =
        result.getStatus() == ForceCloseResult.Status.ACCEPTED
            ? HttpStatus.ACCEPTED
            : HttpStatus.OK;
    return ResponseEntity.status(status).body(result);
  }

  /**
   * Issue #285: operator-triggered orphan adoption. Starts the short-lived {@code AdoptionWorkflow}
   * for the supplied OCC and returns its {@link AdoptionResult} synchronously. The workflow id is
   * keyed on the OCC ({@link WorkflowIds#adoption}) so a double-click maps to one execution and the
   * workflow's own idempotency guard makes a re-run a safe {@code ALREADY_OWNED} no-op.
   *
   * <p>A re-{@code POST} for the same OCC after a prior adoption has already started (in flight) or
   * completed makes {@code stub.start} throw {@link WorkflowExecutionAlreadyStarted} (the workflow
   * id is reused and the default {@code ALLOW_DUPLICATE_FAILED_ONLY} reuse policy rejects the
   * duplicate). Rather than surfacing that as a 500, we attach to the existing execution and return
   * its {@link AdoptionResult} — yielding the idempotent {@code ALREADY_OWNED} (or original {@code
   * ADOPTED}) outcome for both the in-flight and completed cases.
   */
  @PostMapping("/adopt")
  public ResponseEntity<AdoptionResult> adopt(
      HttpServletRequest req, @RequestBody AdoptPayload body) {
    if (body == null || body.occ() == null || body.occ().isBlank()) {
      throw new IllegalArgumentException("occ is required");
    }
    String tenant = ctx.tenantId(req);
    String strategy = ctx.strategyId(req);
    String operator = ctx.operatorId(req);

    AdoptionWorkflowInput in = new AdoptionWorkflowInput();
    in.setSchemaVersion(1L);
    in.setTenantId(tenant);
    in.setStrategyId(strategy);
    in.setOcc(body.occ());
    in.setOperatorId(operator);

    String workflowId = WorkflowIds.adoption(tenant, strategy, body.occ());
    WorkflowOptions opts =
        WorkflowOptions.newBuilder()
            .setWorkflowId(workflowId)
            .setTaskQueue(orchestratorTaskQueue)
            .build();
    WorkflowStub stub = client.newUntypedWorkflowStub(ADOPTION_WORKFLOW_TYPE, opts);
    AdoptionResult result;
    try {
      stub.start(in);
      result = stub.getResult(AdoptionResult.class);
    } catch (WorkflowExecutionAlreadyStarted alreadyStarted) {
      // Same-OCC re-invoke: attach to the existing (in-flight or completed) execution and return
      // its idempotent result instead of 500-ing on the duplicate-start rejection.
      result = client.newUntypedWorkflowStub(workflowId).getResult(AdoptionResult.class);
    }

    // ADOPTED + ALREADY_OWNED are successful (idempotent) outcomes -> 200. The refusals
    // (REFUSED_NOT_HELD / REFUSED_NO_ANCHOR) mean the request can't be fulfilled given current
    // broker/journal state -> 409 Conflict so the operator distinguishes them from a 200.
    HttpStatus status =
        switch (result.getOutcome()) {
          case ADOPTED, ALREADY_OWNED -> HttpStatus.OK;
          case REFUSED_NOT_HELD, REFUSED_NO_ANCHOR -> HttpStatus.CONFLICT;
        };
    return ResponseEntity.status(status).body(result);
  }

  private static Map<String, Object> summarize(WorkflowExecutionMetadata m) {
    Instant start = m.getStartTime();
    return Map.of(
        "workflow_id", m.getExecution().getWorkflowId(),
        "run_id", m.getExecution().getRunId(),
        "start_time", start == null ? "" : start.toString());
  }

  public record ForceClosePayload(String workflowId, String reason) {}

  public record AdoptPayload(String occ) {}
}
