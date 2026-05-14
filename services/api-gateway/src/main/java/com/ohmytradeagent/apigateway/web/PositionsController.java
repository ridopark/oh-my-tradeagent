package com.ohmytradeagent.apigateway.web;

import com.ohmytradeagent.contract.ForceCloseRequest;
import com.ohmytradeagent.contract.ForceCloseResult;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.client.WorkflowStub;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
 *   <li>{@code POST /positions/{workflowId}/force-close} — Update {@code force_close} with
 *       WaitPolicy=Accepted; returns the workflow's {@link ForceCloseResult} payload directly so
 *       the operator can correlate the eventual fill via {@code position_state} polling.
 * </ul>
 */
@RestController
@RequestMapping("/positions")
public class PositionsController {

  private static final String POSITION_WORKFLOW_TYPE = "PositionWorkflow";

  private final WorkflowClient client;
  private final TenantContext ctx;

  public PositionsController(WorkflowClient client, TenantContext ctx) {
    this.client = client;
    this.ctx = ctx;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> list(HttpServletRequest req) {
    String tenant = ctx.tenantId(req);
    String strategy = ctx.strategyId(req);
    String query =
        String.format(
            "TenantStrategy = '%s' AND WorkflowType = '%s' AND ExecutionStatus = '%s'",
            WorkflowIds.tenantStrategy(tenant, strategy),
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
    String operator = ctx.operatorId(req);
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

  private static Map<String, Object> summarize(WorkflowExecutionMetadata m) {
    Instant start = m.getStartTime();
    return Map.of(
        "workflow_id", m.getExecution().getWorkflowId(),
        "run_id", m.getExecution().getRunId(),
        "start_time", start == null ? "" : start.toString());
  }

  public record ForceClosePayload(String workflowId, String reason) {}
}
