package com.ohmytradeagent.apigateway.web;

import com.ohmytradeagent.contract.LivePromotionApprovalRequest;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 7 prep (issue #87) — dual-control sign-off endpoint for live-broker promotion.
 *
 * <p>{@code POST /promotion/approve} routes the request through a Temporal Workflow Update on the
 * per-(tenant, strategy) {@code KillSwitchWorkflow} (the natural carrier for operator events scoped
 * to a tenant/strategy; the kill-switch workflow already runs forever per pair). The Update's
 * Activity validates dual-control approver IDs and — on pass — emits one {@code
 * LivePromotionApproved} audit event via the hash-chain writer (PR #117).
 *
 * <p>Same-approver / blank-approver requests reject with {@code
 * IllegalArgumentException("approvers_must_differ")} surfaced via {@link GlobalExceptionHandler} as
 * HTTP 400 with the error code in the body. This endpoint only records the sign-off; the actual
 * {@code broker_target} ConfigMap flip is operator-driven post-sign-off (see {@code
 * docs/ops/live-promotion-rollback.md §Sign-off recording}).
 */
@RestController
@RequestMapping("/promotion")
public class PromotionController {

  private final WorkflowClient client;
  private final TenantContext ctx;

  public PromotionController(WorkflowClient client, TenantContext ctx) {
    this.client = client;
    this.ctx = ctx;
  }

  @PostMapping("/approve")
  public ResponseEntity<Map<String, Object>> approve(
      HttpServletRequest req, @RequestBody PromotionPayload body) {
    String approver1 = ctx.operatorId(req);
    String approver2 = ctx.approverId2(req);
    // Gateway-side fast-fail for same-approver (matches the Activity-side validator). This keeps
    // the 400 mapping clean via GlobalExceptionHandler.IllegalArgumentException without depending
    // on Temporal's WorkflowUpdateException → 409 wrapping. The orchestrator Activity remains
    // authoritative — it re-runs the same check inside the audit-emitting Activity for defense in
    // depth and the unit tests assert the no-event invariant there.
    if (approver1 != null && approver1.equals(approver2)) {
      throw new IllegalArgumentException("approvers_must_differ");
    }
    String tenant = ctx.tenantId(req);
    String strategy = ctx.strategyId(req);
    PromotionPayload payload = body == null ? new PromotionPayload(null, null) : body;
    String brokerTarget = payload.brokerTarget();
    if (brokerTarget == null || brokerTarget.isBlank()) {
      throw new IllegalArgumentException("broker_target_required");
    }
    String note = payload.note();

    String wfId = WorkflowIds.killswitch(tenant, strategy);

    LivePromotionApprovalRequest lpr = new LivePromotionApprovalRequest();
    lpr.setSchemaVersion(1L);
    lpr.setApproverId1(approver1);
    lpr.setApproverId2(approver2);
    lpr.setTenantId(tenant);
    lpr.setStrategyId(strategy);
    lpr.setBrokerTarget(brokerTarget);
    lpr.setNote(note);

    WorkflowStub stub = client.newUntypedWorkflowStub(wfId);
    stub.update("record_live_promotion", Void.class, lpr);

    return ResponseEntity.ok(
        Map.of(
            "status",
            "APPROVED",
            "tenant_id",
            tenant,
            "strategy_id",
            strategy,
            "broker_target",
            brokerTarget));
  }

  public record PromotionPayload(
      @com.fasterxml.jackson.annotation.JsonProperty("broker_target") String brokerTarget,
      String note) {}
}
