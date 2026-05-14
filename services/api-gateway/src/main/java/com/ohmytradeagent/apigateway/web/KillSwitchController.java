package com.ohmytradeagent.apigateway.web;

import com.ohmytradeagent.contract.KillSwitchState;
import com.ohmytradeagent.contract.ResetKillSwitchRequest;
import com.ohmytradeagent.contract.TripKillSwitchRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowStub;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator endpoints for the kill switch.
 *
 * <ul>
 *   <li>{@code GET /killswitch} — query current state.
 *   <li>{@code POST /killswitch/trip} — trip via {@code trip_killswitch} Update (WaitPolicy is
 *       SDK-default: {@code Accepted} when using {@code update()}; we use {@code startUpdate()}
 *       returning the handle and don't block on the cascade).
 *   <li>{@code POST /killswitch/reset} — dual-approver reset via {@code reset_killswitch} Update.
 *       Validator-side rejection on same approver IDs surfaces here as 400.
 * </ul>
 */
@RestController
@RequestMapping("/killswitch")
public class KillSwitchController {

  private final WorkflowClient client;
  private final TenantContext ctx;

  public KillSwitchController(WorkflowClient client, TenantContext ctx) {
    this.client = client;
    this.ctx = ctx;
  }

  @GetMapping
  public ResponseEntity<KillSwitchState> state(HttpServletRequest req) {
    String tenant = ctx.tenantId(req);
    String strategy = ctx.strategyId(req);
    String wfId = WorkflowIds.killswitch(tenant, strategy);
    try {
      WorkflowStub stub = client.newUntypedWorkflowStub(wfId);
      KillSwitchState state = stub.query("killswitch_state", KillSwitchState.class);
      return ResponseEntity.ok(state);
    } catch (WorkflowNotFoundException e) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
  }

  /** Trip via Update. We don't block on the cascade — the Update returns once the state flips. */
  @PostMapping("/trip")
  public ResponseEntity<Map<String, Object>> trip(
      HttpServletRequest req, @RequestBody TripPayload body) {
    String operator = ctx.operatorId(req);
    String tenant = ctx.tenantId(req);
    String strategy = ctx.strategyId(req);
    String wfId = WorkflowIds.killswitch(tenant, strategy);

    TripKillSwitchRequest tk = new TripKillSwitchRequest();
    tk.setSchemaVersion(1L);
    tk.setReason(body.reason());
    tk.setActor("operator:" + operator);

    WorkflowStub stub = client.newUntypedWorkflowStub(wfId);
    stub.update("trip_killswitch", Void.class, tk);
    return ResponseEntity.ok(
        Map.of("status", "TRIPPED", "tenant_id", tenant, "strategy_id", strategy));
  }

  /** Dual-approver reset. Same-approver rejection from the Validator becomes HTTP 400. */
  @PostMapping("/reset")
  public ResponseEntity<Map<String, Object>> reset(
      HttpServletRequest req, @RequestBody ResetPayload body) {
    String approver1 = ctx.operatorId(req);
    String approver2 = ctx.approverId2(req);
    String tenant = ctx.tenantId(req);
    String strategy = ctx.strategyId(req);
    String wfId = WorkflowIds.killswitch(tenant, strategy);

    ResetKillSwitchRequest rr = new ResetKillSwitchRequest();
    rr.setSchemaVersion(1L);
    rr.setApproverId1(approver1);
    rr.setApproverId2(approver2);
    rr.setNote(body.note());

    WorkflowStub stub = client.newUntypedWorkflowStub(wfId);
    stub.update("reset_killswitch", Void.class, rr);
    return ResponseEntity.ok(
        Map.of("status", "RESET", "tenant_id", tenant, "strategy_id", strategy));
  }

  public record TripPayload(String reason) {}

  public record ResetPayload(String note) {}
}
