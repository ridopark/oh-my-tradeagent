package com.ohmytradeagent.tdbff.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ohmytradeagent.contract.ForceCloseRequest;
import com.ohmytradeagent.contract.ForceCloseResult;
import com.ohmytradeagent.tdbff.positions.PositionsReader;
import com.ohmytradeagent.tdbff.positions.PositionsReader.OpenPosition;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant self-service position-lifecycle controls.
 *
 * <ul>
 *   <li>{@code GET /api/positions} — open positions for the authenticated tenant, all strategies.
 *   <li>{@code POST /api/positions/force-close} — drive the EXISTING {@code
 *       PositionWorkflow.force_close} Update (cancel any in-flight exit → marketable SELL for the
 *       remaining qty; benign no-op / phantom-clear when the broker is already flat). Real-money
 *       capable, so it is dark-launch gated ({@code positions.force-close.write-enabled}) and
 *       tenant-isolated: the tenant is resolved fail-closed from {@code X-Tenant-Id} and the
 *       caller-supplied {@code workflow_id} must belong to that tenant.
 * </ul>
 */
@RestController
@RequestMapping("/api/positions")
public class PositionsController {

  private static final Logger log = LoggerFactory.getLogger(PositionsController.class);

  private final PositionsReader reader;
  private final TenantContext ctx;
  private final WorkflowClient client;

  /**
   * Server-side dark-launch gate for the FORCE-CLOSE WRITE (default false). Mirrors the account
   * kill-switch reset flag: this endpoint can place a marketable SELL against a real-money account,
   * so while off {@code POST /force-close} 404s server-side — the write surface is not merely
   * hidden on the dashboard button. Flipped true only alongside the dashboard's {@code
   * FORCE_EXIT_WRITE_ENABLED}.
   */
  private final boolean forceCloseWriteEnabled;

  public PositionsController(
      PositionsReader reader,
      TenantContext ctx,
      WorkflowClient client,
      @Value("${positions.force-close.write-enabled:false}") boolean forceCloseWriteEnabled) {
    this.reader = reader;
    this.ctx = ctx;
    this.client = client;
    this.forceCloseWriteEnabled = forceCloseWriteEnabled;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> list(HttpServletRequest req) {
    String tenant = ctx.tenantId(req);
    List<OpenPosition> positions = reader.openPositions(tenant);
    List<Map<String, Object>> items = positions.stream().map(PositionsController::item).toList();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tenant_id", tenant);
    body.put("count", items.size());
    body.put("items", items);
    return ResponseEntity.ok(body);
  }

  @PostMapping("/force-close")
  public ResponseEntity<Map<String, Object>> forceClose(
      HttpServletRequest req, @RequestBody(required = false) ForceClosePayload body) {
    if (!forceCloseWriteEnabled) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build(); // dark-launch: write surface off
    }
    String tenant = ctx.tenantId(req); // fail-closed 401 — the tenant is NEVER a client parameter

    String workflowId = body == null ? null : body.workflowId();
    if (workflowId == null || workflowId.isBlank()) {
      throw new IllegalArgumentException("workflow_id is required");
    }
    String reason = body.reason();
    if (reason == null || reason.isBlank()) {
      // The workflow's force_close validator requires a non-blank reason; reject early so a blank
      // reason never reaches Temporal (and audits with a real operator reason).
      throw new IllegalArgumentException("reason is required");
    }

    // Cross-tenant guard (defense-in-depth). The /live view is a TENANT view spanning ALL of the
    // tenant's strategies (unlike the api-gateway force-close, which is single-strategy and guards
    // on tenantStrategy). The SAFEST correct guard here is therefore the tenant segment itself:
    // every PositionWorkflow id begins "t-<tenant>/s-<strategy>/pos/..." (WorkflowIds.position ->
    // tenantStrategy -> "t-"+tenant+"/s-"+strategy), so requiring the "t-<tenant>/" prefix accepts
    // any of the caller's own strategies and rejects any id belonging to another tenant. The
    // trailing "/" makes the tenant segment a hard boundary, so "acme" cannot reach "acme2"'s
    // positions ("t-acme2/..." does not start with "t-acme/"). Rejected BEFORE any Temporal call.
    String requiredPrefix = "t-" + tenant + "/";
    if (!workflowId.startsWith(requiredPrefix)) {
      log.warn(
          "force-close refused: cross_tenant_workflow_id tenant={} workflow_id={}",
          tenant,
          workflowId);
      return ResponseEntity.status(HttpStatus.FORBIDDEN)
          .body(Map.of("error", "cross_tenant_workflow_id"));
    }

    // Only PositionWorkflows are force-closable. The tenant-prefix guard above also accepts the
    // tenant's OWN non-position workflows (killswitch/config/recon ids share the "t-<tenant>/"
    // prefix); those would hit an unknown force_close update. Every PositionWorkflow id is
    // "t-<tenant>/s-<strategy>/pos/<occ>/<entrySignalId>" (WorkflowIds.position), so additionally
    // require the "/pos/" segment. Rejected BEFORE any Temporal call.
    if (!workflowId.contains("/pos/")) {
      log.warn(
          "force-close refused: not_a_position_workflow_id tenant={} workflow_id={}",
          tenant,
          workflowId);
      return ResponseEntity.status(HttpStatus.FORBIDDEN)
          .body(Map.of("error", "not_a_position_workflow_id"));
    }

    // The tenant path attributes the action to the tenant itself (same convention as the account
    // kill-switch reset: operator_id = "tenant:"+tenant). When present, the OPTIONAL X-Operator-Id
    // header threads a verified-actor identity (the Phase-2 dashboard server action sends the
    // verified session email) into the audit subject for per-human attribution on multi-user
    // tenants. NOTE: X-Operator-Id here is attribution-only (recorded in ForceCloseRequested),
    // authorization is the tenant-prefix guard + dark flag.
    String actor = sanitizeActor(req.getHeader("X-Operator-Id"));
    String operatorId = actor.isEmpty() ? "tenant:" + tenant : "tenant:" + tenant + ":" + actor;

    ForceCloseRequest fr = new ForceCloseRequest();
    fr.setSchemaVersion(1L);
    fr.setOperatorId(operatorId);
    fr.setReason(reason);

    WorkflowStub stub = client.newUntypedWorkflowStub(workflowId);
    ForceCloseResult result = stub.update("force_close", ForceCloseResult.class, fr);

    HttpStatus status =
        result.getStatus() == ForceCloseResult.Status.ACCEPTED
            ? HttpStatus.ACCEPTED
            : HttpStatus.OK;
    Map<String, Object> respBody = new LinkedHashMap<>();
    respBody.put("status", result.getStatus());
    respBody.put("exit_signal_id", result.getExitSignalId());
    return ResponseEntity.status(status).body(respBody);
  }

  /**
   * Sanitize the caller-supplied {@code X-Operator-Id} for audit safety. It is attribution, NOT an
   * authz principal (the tenant scope + dark flag are the authz), so we only make it safe to embed
   * in the audit subject: trim, cap length, and keep a conservative char set so it cannot inject
   * weird content. A null/blank/all-stripped actor returns "" → caller falls back to "tenant:"+t.
   */
  private static String sanitizeActor(String raw) {
    if (raw == null) {
      return "";
    }
    String trimmed = raw.trim();
    if (trimmed.length() > 128) {
      trimmed = trimmed.substring(0, 128);
    }
    return trimmed.replaceAll("[^A-Za-z0-9_.@+-]", "");
  }

  private static Map<String, Object> item(OpenPosition p) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("workflow_id", p.workflowId());
    m.put("strategy_id", p.strategyId());
    m.put("contract_symbol", p.contractSymbol());
    m.put("remaining_qty", p.remainingQty());
    m.put("entry_premium", p.entryPremium());
    m.put("open_notional", p.openNotional());
    return m;
  }

  /** {@code {"workflow_id": "...", "reason": "..."}} — matches the dashboard /live JSON body. */
  public record ForceClosePayload(@JsonProperty("workflow_id") String workflowId, String reason) {}
}
