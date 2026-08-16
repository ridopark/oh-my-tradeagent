package com.ohmytradeagent.tdbff.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ohmytradeagent.contract.ArmTrailRequest;
import com.ohmytradeagent.contract.ArmTrailResult;
import com.ohmytradeagent.contract.ForceCloseRequest;
import com.ohmytradeagent.contract.ForceCloseResult;
import com.ohmytradeagent.contract.PartialCloseRequest;
import com.ohmytradeagent.contract.PartialCloseResult;
import com.ohmytradeagent.tdbff.positions.PositionsReader;
import com.ohmytradeagent.tdbff.positions.PositionsReader.OpenPosition;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
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
 *   <li>{@code POST /api/positions/partial-close} — drive the {@code
 *       PositionWorkflow.partial_close} Update (sell {@code fraction} of the remaining qty at
 *       MARKET, leave the rest running). Same real-money guards as force-close, behind its OWN
 *       dark-launch flag ({@code positions.partial-close.write-enabled}).
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

  /**
   * Server-side dark-launch gate for the PARTIAL-CLOSE ("Trim") WRITE (default false). Separate
   * from the force-close flag on purpose: trimming and flattening are independent capabilities, so
   * the operator can enable the reduce-only one without also arming the full-exit one (or vice
   * versa). While off, {@code POST /partial-close} 404s server-side. Flipped true only alongside
   * the dashboard's {@code TRIM_WRITE_ENABLED}.
   */
  private final boolean partialCloseWriteEnabled;

  /**
   * PLAN-2026-08-16 operator trailing stop. A THIRD parameter on the SAME constructor, deliberately
   * — a second constructor is the Spring two-@Autowired-candidate trap that has aborted context
   * refresh here twice. Unlike its two siblings this ships ENABLED by default ({@code
   * positions.arm-trail.write-enabled}, operator decision) — set {@code
   * POSITIONS_ARM_TRAIL_WRITE_ENABLED=false} to disable per cluster. Still gated IN-METHOD rather
   * than with {@code @ConditionalOnProperty}: that annotation removes the whole controller bean,
   * which would also 404 {@code GET /api/positions} and the two existing writes — and that matters
   * MORE now the flag is normally on, because the off state is the exceptional one someone will
   * reach for in a hurry.
   */
  private final boolean armTrailWriteEnabled;

  public PositionsController(
      PositionsReader reader,
      TenantContext ctx,
      WorkflowClient client,
      @Value("${positions.force-close.write-enabled:false}") boolean forceCloseWriteEnabled,
      @Value("${positions.partial-close.write-enabled:false}") boolean partialCloseWriteEnabled,
      @Value("${positions.arm-trail.write-enabled:true}") boolean armTrailWriteEnabled) {
    this.reader = reader;
    this.ctx = ctx;
    this.client = client;
    this.forceCloseWriteEnabled = forceCloseWriteEnabled;
    this.partialCloseWriteEnabled = partialCloseWriteEnabled;
    this.armTrailWriteEnabled = armTrailWriteEnabled;
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
      // dark-launch: write surface off. Return JSON like every other response (the Phase-2 client
      // parses the body) so the disabled state is self-describing rather than a bodiless 404.
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(Map.of("error", "force_close_disabled"));
    }
    String tenant = ctx.tenantId(req); // fail-closed 401 — the tenant is NEVER a client parameter

    String workflowId = body == null ? null : body.workflowId();
    String reason = body == null ? null : body.reason();
    requireWorkflowIdAndReason(workflowId, reason);

    ResponseEntity<Map<String, Object>> refusal = guardWorkflowId(tenant, workflowId);
    if (refusal != null) {
      return refusal;
    }

    String operatorId = operatorId(req, tenant);

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
   * Operator-initiated PARTIAL close ("Trim" on /live): sell {@code fraction} of the position's
   * remaining qty at MARKET and leave the rest running with its exits intact. Drives the {@code
   * PositionWorkflow.partial_close} Update — the reduce-only sibling of {@code force_close}, and
   * therefore behind the SAME guards: its own dark-launch flag, the fail-closed tenant resolution,
   * and the shared tenant-prefix / {@code /pos/} workflow-id guards. Separately flagged from
   * force-close so the operator can enable the two capabilities independently.
   */
  @PostMapping("/partial-close")
  public ResponseEntity<Map<String, Object>> partialClose(
      HttpServletRequest req, @RequestBody(required = false) PartialClosePayload body) {
    if (!partialCloseWriteEnabled) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(Map.of("error", "partial_close_disabled"));
    }
    String tenant = ctx.tenantId(req); // fail-closed 401 — the tenant is NEVER a client parameter

    String workflowId = body == null ? null : body.workflowId();
    String reason = body == null ? null : body.reason();
    requireWorkflowIdAndReason(workflowId, reason);

    // The workflow validator rejects a fraction outside (0,1) exclusive; reject early so the
    // operator gets a 400 naming the field rather than a Temporal update-rejected 409. 1.0 is
    // deliberately NOT accepted here: a full close is force-close's job.
    Double fraction = body.fraction();
    if (fraction == null || fraction <= 0.0 || fraction >= 1.0) {
      throw new IllegalArgumentException("fraction must be between 0 and 1 (exclusive)");
    }

    ResponseEntity<Map<String, Object>> refusal = guardWorkflowId(tenant, workflowId);
    if (refusal != null) {
      return refusal;
    }

    PartialCloseRequest pr = new PartialCloseRequest();
    pr.setSchemaVersion(1L);
    pr.setOperatorId(operatorId(req, tenant));
    pr.setReason(reason);
    pr.setFraction(BigDecimal.valueOf(fraction));

    WorkflowStub stub = client.newUntypedWorkflowStub(workflowId);
    PartialCloseResult result = stub.update("partial_close", PartialCloseResult.class, pr);

    HttpStatus status =
        result.getStatus() == PartialCloseResult.Status.ACCEPTED
            ? HttpStatus.ACCEPTED
            : HttpStatus.OK;
    Map<String, Object> respBody = new LinkedHashMap<>();
    respBody.put("status", result.getStatus());
    respBody.put("exit_signal_id", result.getExitSignalId());
    return ResponseEntity.status(status).body(respBody);
  }

  /**
   * PLAN-2026-08-16: arm the trailing stop (the existing chandelier trail) on ONE position — the
   * /live "Stop-loss" control. Scope is a single PositionWorkflow; this does not touch {@code
   * strategy_config.trail_giveback_pct}, so no other or future position is affected.
   *
   * <p>Status mapping, and why the three-way split matters: {@code ARMED} → 202, {@code
   * ALREADY_ARMED} → 200, {@code REJECTED} → 422. Collapsing 202/200 would paint a green "stop set"
   * over a request that changed nothing (the same trap {@code trimPosition} calls out in {@code
   * bff.ts}), and letting REJECTED ride back on any 2xx is the silent-success failure this feature
   * exists to avoid — an operator believing a real-money position is protected when it is not. 422
   * here means "the workflow refused"; that is distinct from the 409 {@code update_rejected} a
   * validator rejection produces, and from the 409/404 a vanished workflow produces.
   */
  @PostMapping("/arm-trail")
  public ResponseEntity<Map<String, Object>> armTrail(
      HttpServletRequest req, @RequestBody(required = false) ArmTrailPayload body) {
    if (!armTrailWriteEnabled) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(Map.of("error", "arm_trail_disabled"));
    }
    String tenant = ctx.tenantId(req); // fail-closed 401 — the tenant is NEVER a client parameter

    String workflowId = body == null ? null : body.workflowId();
    if (workflowId == null || workflowId.isBlank()) {
      throw new IllegalArgumentException("workflow_id is required");
    }

    // Pre-validated HERE, not left to the workflow validator: a rejection there returns 409
    // update_rejected, which reads to an operator as a system fault rather than a value they can
    // correct. Same reasoning as partial_close's fraction check above.
    //
    // MAX_GIVEBACK now lives in three places — PositionWorkflowImpl.MAX_GIVEBACK,
    // contract/schemas/arm-trail-request.json, and this bound. They must not drift: a value this
    // controller accepts but the workflow refuses turns an operator's stop into a 409.
    Double giveback = body.givebackPct();
    if (giveback == null || giveback <= 0.0 || giveback > 0.5) {
      throw new IllegalArgumentException(
          "giveback_pct must be between 0 and 0.5 (0 exclusive, 0.5 inclusive)");
    }

    ResponseEntity<Map<String, Object>> refusal = guardWorkflowId(tenant, workflowId);
    if (refusal != null) {
      return refusal;
    }

    ArmTrailRequest ar = new ArmTrailRequest();
    ar.setSchemaVersion(1L);
    ar.setOperatorId(operatorId(req, tenant));
    ar.setGivebackPct(BigDecimal.valueOf(giveback));
    // peak_premium is deliberately NOT threaded from the client. The workflow resolves the anchor
    // itself from its own tracked bid / a fresh quote; a page-rendered premium is seconds stale,
    // and an anchor that is too low sets the stop too low on a real-money position.

    WorkflowStub stub = client.newUntypedWorkflowStub(workflowId);
    ArmTrailResult result = stub.update("arm_trail", ArmTrailResult.class, ar);

    HttpStatus status;
    if (result.getStatus() == ArmTrailResult.Status.ARMED) {
      status = HttpStatus.ACCEPTED;
    } else if (result.getStatus() == ArmTrailResult.Status.ALREADY_ARMED) {
      status = HttpStatus.OK;
    } else {
      status = HttpStatus.UNPROCESSABLE_ENTITY;
    }
    Map<String, Object> respBody = new LinkedHashMap<>();
    respBody.put("status", result.getStatus());
    respBody.put("reason", result.getReason());
    respBody.put("peak_premium", result.getPeakPremium());
    respBody.put("giveback_pct", result.getGivebackPct());
    respBody.put("stop_price", result.getStopPrice());
    return ResponseEntity.status(status).body(respBody);
  }

  /**
   * Both position-lifecycle writes address a workflow and audit an operator reason; neither the
   * force_close nor the partial_close validator accepts a blank one, so reject early (400) rather
   * than round-tripping a doomed Update to Temporal.
   */
  private static void requireWorkflowIdAndReason(String workflowId, String reason) {
    if (workflowId == null || workflowId.isBlank()) {
      throw new IllegalArgumentException("workflow_id is required");
    }
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("reason is required");
    }
  }

  /**
   * Workflow-id guards for every position-lifecycle write, applied BEFORE any Temporal call.
   * Delegates to {@link WorkflowWriteGuards} — the ONE implementation of the tenant boundary,
   * shared with the manual-entry routes — pinning the kind to {@code "/pos/"} because every
   * PositionWorkflow id is {@code "t-<tenant>/s-<strategy>/pos/<occ>/<entrySignalId>"} ({@code
   * WorkflowIds.position}). Without the kind check the tenant-prefix guard would also accept the
   * tenant's OWN killswitch/config/recon workflows, which would hit an unknown update.
   */
  private ResponseEntity<Map<String, Object>> guardWorkflowId(String tenant, String workflowId) {
    return WorkflowWriteGuards.refuseUnlessTenantOwned(
        tenant, workflowId, "/pos/", "not_a_position_workflow_id");
  }

  /** See {@link WorkflowWriteGuards#operatorId} — attribution only, never an authz principal. */
  private String operatorId(HttpServletRequest req, String tenant) {
    return WorkflowWriteGuards.operatorId(req, tenant);
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

  /**
   * {@code {"workflow_id": "...", "reason": "...", "fraction": 0.5}} — the /live Trim body. {@code
   * fraction} is the share of the REMAINING qty to sell; the workflow closes {@code
   * min(remainingQty, ceil(remainingQty * fraction))} contracts.
   */
  public record PartialClosePayload(
      @JsonProperty("workflow_id") String workflowId, String reason, Double fraction) {}

  /**
   * {@code {"workflow_id": "...", "giveback_pct": 0.15}} — the /live Stop-loss body. No {@code
   * reason} field: {@code ArmTrailRequest} has none, so {@code requireWorkflowIdAndReason} is
   * deliberately NOT reused here. {@code @JsonProperty} is load-bearing on both fields — without it
   * the wire names would be {@code workflowId}/{@code givebackPct} and the snake_case body the
   * dashboard sends would bind null silently.
   */
  public record ArmTrailPayload(
      @JsonProperty("workflow_id") String workflowId,
      @JsonProperty("giveback_pct") Double givebackPct) {}
}
