package com.ohmytradeagent.tdbff.web;

import com.fasterxml.jackson.annotation.JsonProperty;
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

  public PositionsController(
      PositionsReader reader,
      TenantContext ctx,
      WorkflowClient client,
      @Value("${positions.force-close.write-enabled:false}") boolean forceCloseWriteEnabled,
      @Value("${positions.partial-close.write-enabled:false}") boolean partialCloseWriteEnabled) {
    this.reader = reader;
    this.ctx = ctx;
    this.client = client;
    this.forceCloseWriteEnabled = forceCloseWriteEnabled;
    this.partialCloseWriteEnabled = partialCloseWriteEnabled;
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
   * Shared workflow-id guards for every position-lifecycle write, applied BEFORE any Temporal call.
   * Returns a 403 refusal, or {@code null} when the id is addressable by this tenant.
   *
   * <p>Cross-tenant guard (defense-in-depth): the /live view is a TENANT view spanning ALL of the
   * tenant's strategies (unlike the api-gateway force-close, which is single-strategy and guards on
   * tenantStrategy). The SAFEST correct guard here is therefore the tenant segment itself: every
   * PositionWorkflow id begins "t-&lt;tenant&gt;/s-&lt;strategy&gt;/pos/..." (WorkflowIds.position
   * -&gt; tenantStrategy -&gt; "t-"+tenant+"/s-"+strategy), so requiring the "t-&lt;tenant&gt;/"
   * prefix accepts any of the caller's own strategies and rejects any id belonging to another
   * tenant. The trailing "/" makes the tenant segment a hard boundary, so "acme" cannot reach
   * "acme2"'s positions ("t-acme2/..." does not start with "t-acme/").
   *
   * <p>Position guard: the tenant-prefix guard also accepts the tenant's OWN non-position workflows
   * (killswitch/config/recon ids share the "t-&lt;tenant&gt;/" prefix); those would hit an unknown
   * update. Every PositionWorkflow id is "t-&lt;tenant&gt;/s-&lt;strategy&gt;/pos/&lt;occ&gt;/&lt;
   * entrySignalId&gt;" (WorkflowIds.position), so additionally require the "/pos/" segment.
   */
  private ResponseEntity<Map<String, Object>> guardWorkflowId(String tenant, String workflowId) {
    String requiredPrefix = "t-" + tenant + "/";
    if (!workflowId.startsWith(requiredPrefix)) {
      return refuseForbidden("cross_tenant_workflow_id", tenant, workflowId);
    }
    if (!workflowId.contains("/pos/")) {
      return refuseForbidden("not_a_position_workflow_id", tenant, workflowId);
    }
    return null;
  }

  /**
   * The tenant path attributes the action to the tenant itself (same convention as the account
   * kill-switch reset: operator_id = "tenant:"+tenant). When present, the OPTIONAL X-Operator-Id
   * header threads a verified-actor identity (the dashboard server action sends the verified
   * session email) into the audit subject for per-human attribution on multi-user tenants. NOTE:
   * X-Operator-Id is attribution-only (recorded on the ForceCloseRequested / OperatorTrimRequested
   * audit); authorization is the tenant-prefix guard + the dark flag.
   */
  private String operatorId(HttpServletRequest req, String tenant) {
    String actor = sanitizeActor(req.getHeader(TenantContext.HEADER_OPERATOR));
    return actor.isEmpty() ? "tenant:" + tenant : "tenant:" + tenant + ":" + actor;
  }

  /**
   * Refuse a position-lifecycle write BEFORE any Temporal call: log the reason and return a 403
   * whose body names the guard that rejected it. Shared by the cross-tenant and not-a-position
   * guards (identical shape, differing only by {@code code}).
   */
  private ResponseEntity<Map<String, Object>> refuseForbidden(
      String code, String tenant, String workflowId) {
    // Endpoint-neutral wording: this guard is shared by force-close and partial-close, so naming
    // one of them would mis-attribute the other's refusals during a cross-tenant probe triage.
    log.warn("position write refused: {} tenant={} workflow_id={}", code, tenant, workflowId);
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", code));
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

  /**
   * {@code {"workflow_id": "...", "reason": "...", "fraction": 0.5}} — the /live Trim body. {@code
   * fraction} is the share of the REMAINING qty to sell; the workflow closes {@code
   * min(remainingQty, ceil(remainingQty * fraction))} contracts.
   */
  public record PartialClosePayload(
      @JsonProperty("workflow_id") String workflowId, String reason, Double fraction) {}
}
