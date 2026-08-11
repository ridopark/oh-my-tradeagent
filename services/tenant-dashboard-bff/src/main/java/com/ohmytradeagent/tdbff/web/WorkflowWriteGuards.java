package com.ohmytradeagent.tdbff.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Shared tenant-isolation guards for every BFF surface that addresses a Temporal workflow by id and
 * can move real money — today {@code POST /api/positions/force-close}, {@code POST
 * /api/positions/partial-close} (PLAN-2026-08-05-live-trim-button) and the manual-entry routes
 * (PLAN-2026-08-10-live-manual-bto).
 *
 * <p>Extracted so the tenant boundary is enforced by ONE piece of code. A copy-per-controller would
 * mean the next write surface silently inherits whichever variant its author pasted — and the guard
 * that stops tenant {@code acme} from reaching tenant {@code acme2}'s positions is not a thing to
 * have two versions of.
 */
final class WorkflowWriteGuards {

  private static final Logger log = LoggerFactory.getLogger(WorkflowWriteGuards.class);

  /** Cap on the audit-only actor string; see {@link #sanitizeActor}. */
  private static final int MAX_ACTOR_LENGTH = 128;

  private WorkflowWriteGuards() {}

  /**
   * Refuse a workflow-addressing write BEFORE any Temporal call. Returns a 403 refusal, or {@code
   * null} when the id is addressable by this tenant.
   *
   * <p><b>Cross-tenant guard (defense-in-depth).</b> Every workflow id this tenant owns begins
   * {@code "t-<tenant>/"} ({@code WorkflowIds.tenantStrategy}), so requiring that prefix accepts
   * any of the caller's own strategies and rejects another tenant's. The trailing {@code "/"} makes
   * the tenant segment a hard boundary, so {@code "acme"} cannot reach {@code "acme2"} ({@code
   * "t-acme2/..."} does not start with {@code "t-acme/"}).
   *
   * <p><b>Kind guard.</b> The tenant-prefix check also accepts the tenant's OWN unrelated workflows
   * (killswitch/config/recon ids share the prefix), which would hit an unknown update or query.
   * {@code requiredSegment} pins the kind: {@code "/pos/"} for a PositionWorkflow, {@code "/sig/"}
   * for a CopytradeSignalWorkflow.
   *
   * @param wrongKindCode error code returned when the id is the tenant's but the wrong kind
   */
  static ResponseEntity<Map<String, Object>> refuseUnlessTenantOwned(
      String tenant, String workflowId, String requiredSegment, String wrongKindCode) {
    String requiredPrefix = "t-" + tenant + "/";
    if (!workflowId.startsWith(requiredPrefix)) {
      return refuseForbidden("cross_tenant_workflow_id", tenant, workflowId);
    }
    if (!workflowId.contains(requiredSegment)) {
      return refuseForbidden(wrongKindCode, tenant, workflowId);
    }
    return null;
  }

  /**
   * The tenant path attributes the action to the tenant itself (same convention as the account
   * kill-switch reset: {@code operator_id = "tenant:"+tenant}). When present, the OPTIONAL {@code
   * X-Operator-Id} header threads a verified-actor identity (the dashboard server action sends the
   * verified session email) into the audit subject for per-human attribution on multi-user tenants.
   *
   * <p>NOTE: {@code X-Operator-Id} is attribution-only; authorization is the tenant-prefix guard
   * plus the route's dark flag.
   */
  static String operatorId(HttpServletRequest req, String tenant) {
    String actor = sanitizeActor(req.getHeader(TenantContext.HEADER_OPERATOR));
    return actor.isEmpty() ? "tenant:" + tenant : "tenant:" + tenant + ":" + actor;
  }

  /**
   * Sanitize the caller-supplied {@code X-Operator-Id} for audit safety. It is attribution, NOT an
   * authz principal, so we only make it safe to embed in an audit subject: trim, cap length, and
   * keep a conservative char set so it cannot inject weird content. A null/blank/all-stripped actor
   * returns {@code ""} → the caller falls back to {@code "tenant:"+tenant}.
   */
  static String sanitizeActor(String raw) {
    if (raw == null) {
      return "";
    }
    String trimmed = raw.trim();
    if (trimmed.length() > MAX_ACTOR_LENGTH) {
      trimmed = trimmed.substring(0, MAX_ACTOR_LENGTH);
    }
    return trimmed.replaceAll("[^A-Za-z0-9_.@+-]", "");
  }

  /**
   * Log the refusal and return a 403 whose body names the guard that rejected it. Endpoint-neutral
   * wording: this guard is shared, so naming one route would mis-attribute another's refusals
   * during a cross-tenant probe triage.
   */
  private static ResponseEntity<Map<String, Object>> refuseForbidden(
      String code, String tenant, String workflowId) {
    log.warn("workflow write refused: {} tenant={} workflow_id={}", code, tenant, workflowId);
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", code));
  }
}
