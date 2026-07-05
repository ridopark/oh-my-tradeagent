package com.ohmytradeagent.apigateway.web;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.bootstrap.StrategyConfigInvariants;
import com.ohmytradeagent.orchestrator.workflows.TenantDeleteResult;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongConsumer;
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

/**
 * Operator tenant-delete entrypoint (PLAN-2026-07-03, Phase 4) — the REAL-MONEY safety gate. {@code
 * POST /admin/tenants/{tenant}/delete} de-provisions a dark, never-traded tenant. It is
 * <b>structurally incapable</b> of touching a live tenant: P0 (LIVE_BROKER_TARGET) is evaluated
 * first and unconditionally, and the downstream teardown never runs for a {@code -live} tenant.
 *
 * <p><b>Pre-flight guards, IN ORDER, first-failure → 409 {@code {blocked_by, detail}}, any
 * unreadable signal → BLOCK (fail-closed):</b>
 *
 * <ol>
 *   <li><b>P0 ALLOWLIST</b> — every strategy's {@code broker_target} must be definitely-paper
 *       ({@link #isDefinitelyPaper}); a {@code -live} target → {@code LIVE_BROKER_TARGET}, a
 *       null/blank/bare/unknown target → {@code NON_PAPER_BROKER_TARGET}, zero rows → {@code
 *       UNKNOWN_TENANT_SHAPE}, an unparseable row → block (never skip).
 *   <li><b>MULTI_STRATEGY_UNSUPPORTED</b> — a tenant with &gt;1 strategy is out of scope (409),
 *       checked AFTER P0 (so a {@code -live} row still reports {@code LIVE_BROKER_TARGET} first)
 *       and before any side effect. This route is single-strategy only: the teardown loops a
 *       per-strategy workflow with P4/P5 checked inside each, so strategy A could irreversibly
 *       complete before strategy B blocks. Correct multi-strategy teardown needs a future two-pass
 *       gate (check P4/P5 for ALL strategies before tearing down ANY).
 *   <li><b>P1 ACTIVE_LIVE_ACTIVATION</b> — no strategy has an active live promotion.
 *   <li><b>P2 STRATEGY_ENABLED</b> — every strategy {@code enabled=false} (dark).
 *   <li><b>P3 OPEN_WORKFLOWS</b> — zero RUNNING PositionWorkflow executions.
 * </ol>
 *
 * <p>Then {@code confirm_tenant_id} must string-equal the path {@code {tenant}} (case-sensitive) or
 * 400 {@code CONFIRM_MISMATCH} — checked BEFORE the guards so a typo never triggers reads. On
 * all-pass: emit {@code TenantDeleteRequested} → disable all strategies (disarm-first) →
 * start+await the orchestrator {@link
 * com.ohmytradeagent.orchestrator.workflows.TenantDeleteWorkflow} per strategy (which runs the
 * P4/P5 broker/journal gates then does the FIRST IRREVERSIBLE delete: {@code strategy_config} + the
 * kill switches) → on COMPLETED call exec creds delete → BFF dashboard-rows delete LAST (the only
 * over-the-network store, and reversible/idempotent — torn down last with a bounded retry so a
 * P4/P5-BLOCKED or workflow-faulted delete never reaches it, leaving a SURVIVING tenant's dashboard
 * members untouched) → emit {@code TenantDeleteCompleted}. A workflow BLOCKED (P4/P5) → 409 (no
 * further downstream call, dashboard members never touched); a step fault → 207 + {@code
 * TenantDeleteStepFailed} with {@code deleted_stores}/{@code completed_steps} so the operator can
 * re-run the idempotent tail to converge. Every step is idempotent.
 *
 * <p><b>Dark by construction.</b> Gated on {@code operator.tenant-delete.enabled=true}; unset → the
 * bean does not exist → 404. {@link com.ohmytradeagent.apigateway.security.ServiceTokenFilter}
 * bearer-gates {@code /admin/tenants/}, and this controller requires an ALLOWLISTED {@code
 * X-Operator-Id} (400 absent/malformed, 403 non-allowlisted).
 */
@RestController
@RequestMapping("/admin/tenants")
@ConditionalOnProperty(name = "operator.tenant-delete.enabled", havingValue = "true")
public class TenantDeleteController {

  private static final Logger log = LoggerFactory.getLogger(TenantDeleteController.class);
  private static final List<String> RETAINED_STORES = List.of("audit_log", "order_intent_journal");

  /**
   * Bounded retry for the LAST-step bff dashboard-rows delete — the only over-the-network store,
   * whose DELETE is idempotent so retry is safe. Rides out a transient blip; exhaustion → a loud
   * 207 so the operator re-runs the idempotent tail. Small backoff keeps the request bounded.
   */
  static final int DASHBOARD_DELETE_MAX_ATTEMPTS = 3;

  private static final long DASHBOARD_DELETE_BACKOFF_MS = 250L;

  private final TenantContext ctx;
  private final StrategyConfigReader reader;
  private final LiveActivationStateReader liveActivation;
  private final OpenPositionWorkflowChecker openPositions;
  private final StrategyDisableClient disable;
  private final TenantDeleteWorkflowClient workflow;
  private final BrokerCredentialDeleteForwarder execCreds;
  private final DashboardRowsDeleteForwarder dashboardRows;
  private final TenantDeleteHistoryReader deleteHistory;
  private final TenantDeleteAuditEmitter audit;

  /**
   * Backoff sleeper for the dashboard-rows retry; overridable in tests so they never truly sleep.
   */
  private LongConsumer dashboardRetrySleeper =
      millis -> {
        try {
          Thread.sleep(millis);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
      };

  public TenantDeleteController(
      TenantContext ctx,
      StrategyConfigReader reader,
      LiveActivationStateReader liveActivation,
      OpenPositionWorkflowChecker openPositions,
      StrategyDisableClient disable,
      TenantDeleteWorkflowClient workflow,
      BrokerCredentialDeleteForwarder execCreds,
      DashboardRowsDeleteForwarder dashboardRows,
      TenantDeleteHistoryReader deleteHistory,
      TenantDeleteAuditEmitter audit) {
    this.ctx = ctx;
    this.reader = reader;
    this.liveActivation = liveActivation;
    this.openPositions = openPositions;
    this.disable = disable;
    this.workflow = workflow;
    this.execCreds = execCreds;
    this.dashboardRows = dashboardRows;
    this.deleteHistory = deleteHistory;
    this.audit = audit;
  }

  /** Test seam: replace the backoff sleeper so unit tests exhaust retries without real sleeps. */
  void setDashboardRetrySleeper(LongConsumer sleeper) {
    this.dashboardRetrySleeper = sleeper;
  }

  /**
   * Bounded retry around the idempotent bff dashboard-rows DELETE. Retries a transient transport
   * fault (the observed failure mode is a {@code ResourceAccessException}) up to {@link
   * #DASHBOARD_DELETE_MAX_ATTEMPTS} attempts with a short fixed backoff; rethrows the last fault so
   * the caller reports a loud 207. Safe because the bff DELETE is idempotent (0 rows = success).
   */
  private DashboardRowsDeleteForwarder.DeletedCounts deleteDashboardRowsWithRetry(
      String tenant, String operator) {
    RuntimeException last = null;
    for (int attempt = 1; attempt <= DASHBOARD_DELETE_MAX_ATTEMPTS; attempt++) {
      try {
        return dashboardRows.delete(tenant, operator);
      } catch (RuntimeException e) {
        last = e;
        log.warn(
            "tenant-delete dashboard-rows delete attempt {}/{} failed tenant={} cause={}",
            attempt,
            DASHBOARD_DELETE_MAX_ATTEMPTS,
            tenant,
            e.getClass().getName());
        if (attempt < DASHBOARD_DELETE_MAX_ATTEMPTS) {
          dashboardRetrySleeper.accept(DASHBOARD_DELETE_BACKOFF_MS);
        }
      }
    }
    throw last;
  }

  @PostMapping("/{tenant}/delete")
  public ResponseEntity<Map<String, Object>> delete(
      HttpServletRequest req,
      @PathVariable("tenant") String tenant,
      @RequestBody(required = false) TenantDeleteRequestBody body) {

    String operator =
        ctx.requireAllowlistedOperator(req); // 400 if absent/malformed, 403 if not allowlisted
    String actor = "operator:" + operator;
    String correlationId = UUID.randomUUID().toString();

    // Malformed {tenant} path var → plain 400 (no guard read, no side effect, no audit). The tenant
    // flows into a Temporal workflow id / Visibility query / the exec X-Tenant-Id header, so reject
    // a hostile value up front using TenantContext's canonical charset — same as the sibling
    // operator routes. Checked before/with confirm so a bad path never triggers a read.
    if (!ctx.isValidTenantId(tenant)) {
      return blocked(HttpStatus.BAD_REQUEST, "INVALID_TENANT_ID", "malformed tenant path variable");
    }

    // Confirm match FIRST (cheap, no reads) — a typo never triggers a guard read or side effect.
    String confirm = body == null ? null : body.confirmTenantId();
    if (confirm == null || !confirm.equals(tenant)) {
      return blocked(
          HttpStatus.BAD_REQUEST, "CONFIRM_MISMATCH", "confirm_tenant_id must equal path");
    }

    // ---- P0 ALLOWLIST (load-bearing, first, non-overridable). Single read feeds P0 + P2. ----
    List<StrategyConfigReader.StrategyRow> rows;
    try {
      rows = reader.listByTenant(tenant);
    } catch (RuntimeException e) {
      // An unreadable / unparseable strategy row → cannot prove paper → fail closed.
      return preflightBlock(
          tenant,
          "*",
          actor,
          correlationId,
          "NON_PAPER_BROKER_TARGET",
          "unreadable strategy_config row (fail-closed)");
    }
    if (rows.isEmpty()) {
      return preflightBlock(
          tenant, "*", actor, correlationId, "UNKNOWN_TENANT_SHAPE", "no strategy_config rows");
    }
    boolean anyLive = false;
    boolean anyNonPaper = false;
    for (StrategyConfigReader.StrategyRow row : rows) {
      StrategyConfig cfg = row.config();
      if (StrategyConfigInvariants.isLive(cfg)) {
        anyLive = true;
      } else if (!isDefinitelyPaper(cfg)) {
        anyNonPaper = true;
      }
    }
    String primary = rows.get(0).strategyId();
    if (anyLive) {
      // The single control that makes deleting a real-money tenant impossible.
      return preflightBlock(
          tenant,
          primary,
          actor,
          correlationId,
          "LIVE_BROKER_TARGET",
          "a strategy routes to a -live broker_target");
    }
    if (anyNonPaper) {
      return preflightBlock(
          tenant,
          primary,
          actor,
          correlationId,
          "NON_PAPER_BROKER_TARGET",
          "a strategy has a non-paper broker_target");
    }

    // ---- MULTI_STRATEGY_UNSUPPORTED (single-strategy scope only). Evaluated AFTER P0 (so a
    // multi-strategy tenant with ANY -live row still reports LIVE_BROKER_TARGET first) and BEFORE
    // P1/P2/P3 and any side effect. Multi-strategy teardown loops a TenantDeleteWorkflow per
    // strategy with P4/P5 checked INSIDE each, so strategy A could COMPLETE its irreversible
    // teardown before strategy B evaluates P4/P5 and returns BLOCKED. Correct multi-strategy
    // teardown needs a two-pass gate (check P4/P5 for ALL strategies before tearing down ANY);
    // until then, reject here so the partial-teardown path is unreachable. ----
    if (rows.size() > 1) {
      return preflightBlock(
          tenant,
          "*",
          actor,
          correlationId,
          "MULTI_STRATEGY_UNSUPPORTED",
          "tenant has "
              + rows.size()
              + " strategies; multi-strategy delete is not yet supported (single-strategy only)");
    }

    // ---- P1 ACTIVE_LIVE_ACTIVATION (fail-closed) ----
    try {
      for (StrategyConfigReader.StrategyRow row : rows) {
        if (liveActivation.isActive(tenant, row.strategyId(), brokerTargetOf(row.config()))) {
          return preflightBlock(
              tenant,
              row.strategyId(),
              actor,
              correlationId,
              "ACTIVE_LIVE_ACTIVATION",
              "strategy has an active live promotion: " + row.strategyId());
        }
      }
    } catch (RuntimeException e) {
      return preflightBlock(
          tenant,
          primary,
          actor,
          correlationId,
          "ACTIVE_LIVE_ACTIVATION",
          "live-promotion read faulted (fail-closed)");
    }

    // ---- P2 STRATEGY_ENABLED (from the same rows read) ----
    for (StrategyConfigReader.StrategyRow row : rows) {
      if (Boolean.TRUE.equals(row.config().getEnabled())) {
        return preflightBlock(
            tenant,
            row.strategyId(),
            actor,
            correlationId,
            "STRATEGY_ENABLED",
            "strategy still enabled (disable first): " + row.strategyId());
      }
    }

    // ---- P3 OPEN_WORKFLOWS (fail-closed) ----
    try {
      for (StrategyConfigReader.StrategyRow row : rows) {
        if (openPositions.hasOpen(tenant, row.strategyId())) {
          return preflightBlock(
              tenant,
              row.strategyId(),
              actor,
              correlationId,
              "OPEN_WORKFLOWS",
              "open PositionWorkflow for strategy: " + row.strategyId());
        }
      }
    } catch (RuntimeException e) {
      return preflightBlock(
          tenant,
          primary,
          actor,
          correlationId,
          "OPEN_WORKFLOWS",
          "open-workflow read faulted (fail-closed)");
    }

    // ---- All pre-flight passed → orchestrate (idempotent, disarm-first, re-runnable) ----
    long startMillis = System.currentTimeMillis();

    // 1. TenantDeleteRequested.
    audit.emit(
        "TenantDeleteRequested",
        tenant,
        primary,
        actor,
        correlationId,
        requestedSubject(tenant, confirm, operator, rows));

    // 2. Disable all strategies (disarm-first — P2 already holds, this re-asserts at execution
    // time). Wrapped so a disable fault yields an audited TenantDeleteStepFailed + clean response,
    // never a raw 500. Disable is disarm-first + idempotent, so a partial disable leaves the tenant
    // MORE dark (safe), and NO store has been torn down yet → completed_steps is empty.
    // Re-runnable.
    try {
      for (StrategyConfigReader.StrategyRow row : rows) {
        disable.disable(
            tenant, row.strategyId(), actor, correlationId + "-disable-" + row.strategyId());
      }
    } catch (RuntimeException e) {
      return stepFailed(tenant, primary, actor, correlationId, "disable", new ArrayList<>(), e);
    }

    // 3. Start + await the teardown workflow per strategy (it runs the P4/P5 broker/journal gates
    // then deletes strategy_config + the kill switches — the FIRST IRREVERSIBLE step). A workflow
    // BLOCKED (P4/P5) or fault returns HERE, so the downstream store deletes — including the bff
    // dashboard-rows delete — are NEVER reached: a SURVIVING (blocked) tenant keeps its dashboard
    // members and its config intact.
    for (StrategyConfigReader.StrategyRow row : rows) {
      TenantDeleteResult result;
      try {
        result =
            workflow.deleteTenant(
                tenant, row.strategyId(), brokerTargetOf(row.config()), actor, correlationId);
      } catch (RuntimeException e) {
        // A teardown-workflow fault (activity permanently failing, run timeout,
        // WorkflowFailedException) yields NO COMPLETED result, so the exec/bff store deletes below
        // are never reached (fail-closed). Convert the uncaught throw into a clean, audited
        // response instead of a raw 500 — no store delete completed before the workflow, so
        // completed_steps is empty.
        return stepFailed(
            tenant,
            row.strategyId(),
            actor,
            correlationId,
            "tenant_delete_workflow",
            new ArrayList<>(),
            e);
      }
      if (result.getStatus() == TenantDeleteResult.Status.BLOCKED) {
        String blockedBy = result.getBlockedBy() == null ? "BLOCKED" : result.getBlockedBy().name();
        audit.emit(
            "TenantDeleteBlocked",
            tenant,
            row.strategyId(),
            actor,
            correlationId,
            Map.of("blocked_by", blockedBy, "strategy_id", row.strategyId()));
        return blocked(
            HttpStatus.CONFLICT, blockedBy, "teardown blocked for strategy " + row.strategyId());
      }
    }

    // 4. + 5. Downstream store deletes (the workflow already deleted strategy_config + tombstone).
    // completedSteps is derived from deletedStores' (insertion-ordered) keys at each use site.
    Map<String, Object> deletedStores = new LinkedHashMap<>();
    deletedStores.put("strategy_config", rows.size());

    // 4. exec broker_credentials delete, one call per distinct paper provider.
    try {
      int credsDeleted = 0;
      for (String provider : distinctPaperProviders(rows)) {
        credsDeleted += execCreds.delete(tenant, provider);
      }
      deletedStores.put("broker_credentials", credsDeleted);
    } catch (RuntimeException e) {
      return stepFailed(
          tenant,
          primary,
          actor,
          correlationId,
          "broker_credentials",
          new ArrayList<>(deletedStores.keySet()),
          e);
    }

    // 5. BFF dashboard_user + dashboard_user_invite delete — LAST, only after the irreversible
    // stores are gone. The bff is the ONLY store api-gateway reaches over the network, so it is the
    // one most likely to be transiently unreachable; the DELETE is idempotent, so wrap it in a
    // BOUNDED retry to ride out a transient blip. If it still faults after all attempts (a genuine
    // bff OUTAGE) we fail LOUD with a 207 + TenantDeleteStepFailed carrying the already-deleted
    // stores in completed_steps so the operator can re-run the (idempotent) dashboard-rows delete
    // to
    // converge — the dashboard members are the only residual, bound to an already-config-deleted
    // tenant.
    try {
      DashboardRowsDeleteForwarder.DeletedCounts counts =
          deleteDashboardRowsWithRetry(tenant, operator);
      deletedStores.put("dashboard_user", counts.users());
      deletedStores.put("dashboard_user_invite", counts.invites());
    } catch (RuntimeException e) {
      return stepFailed(
          tenant,
          primary,
          actor,
          correlationId,
          "dashboard_user",
          new ArrayList<>(deletedStores.keySet()),
          e);
    }

    // 6. TenantDeleteCompleted.
    long durationMs = System.currentTimeMillis() - startMillis;
    Map<String, Object> completedSubject = new LinkedHashMap<>();
    completedSubject.put("deleted_stores", deletedStores);
    completedSubject.put("retained_stores", RETAINED_STORES);
    completedSubject.put("duration_ms", durationMs);
    audit.emit("TenantDeleteCompleted", tenant, primary, actor, correlationId, completedSubject);

    Map<String, Object> ok = new LinkedHashMap<>();
    ok.put("status", "DELETED");
    ok.put("deleted_stores", deletedStores);
    ok.put("retained_stores", RETAINED_STORES);
    ok.put("duration_ms", durationMs);
    return ResponseEntity.ok(ok);
  }

  /**
   * The closed set of known paper credential providers. Today effectively {@code {alpaca}} (the
   * only broker), enumerated from the ONE paper-provider home {@link
   * VerifiedAccountGuard#paperProvider}: {@code alpaca-paper} → {@code alpaca}. Used ONLY by the
   * residual-cleanup route (option b): with zero strategy_config rows we cannot derive the provider
   * from {@link #distinctPaperProviders}, so we iterate the known paper providers instead. The exec
   * delete is idempotent (0 rows = success), so a provider the tenant never used is a harmless
   * no-op.
   */
  static final List<String> KNOWN_PAPER_PROVIDERS =
      List.of(VerifiedAccountGuard.paperProvider("alpaca-paper"));

  /**
   * Residual-cleanup carrier for a PARTIALLY-deleted tenant: {@code POST
   * /admin/tenants/{tenant}/cleanup-residual}. Converges a tenant whose {@code strategy_config} was
   * ALREADY workflow-deleted (zero rows) but whose idempotent residual stores ({@code
   * broker_credentials} / dashboard rows) survived a step fault on the original {@link #delete}, so
   * the operator no longer hits the P0 {@code UNKNOWN_TENANT_SHAPE} 409 the normal delete route
   * returns on zero rows.
   *
   * <p><b>Residual-only paper-safety invariant (STRUCTURALLY ENFORCED).</b> A genuinely residual
   * tenant reached zero {@code strategy_config} rows ONLY by having ALREADY passed the full P0
   * paper-allowlist on the original {@link #delete} (a {@code -live} / non-paper / multi-strategy
   * tenant is refused BEFORE any teardown) and having had its config + kill switches
   * workflow-deleted, so it touches ONLY the two idempotent residual stores and can NEVER reach a
   * workflow, a kill switch, or a {@code -live} broker path. Zero rows ALONE is NOT proof of that:
   * a tenant that was NEVER created also has zero rows (the onboard invite step is independent of
   * tenant creation, so an operator can invite a user for a tenant_id before its config exists). So
   * this route enforces the invariant with TWO gates: (a) strategy_config rows == 0, AND (b) audit
   * evidence — via {@link TenantDeleteHistoryReader} — that a delete was actually attempted ({@code
   * TenantDeleteRequested}, emitted only after P0–P3 pass, or {@code TenantDeleteStepFailed}). If
   * strategy_config still has ≥1 row, OR no prior delete was attempted, the tenant is NOT residual
   * → this route REFUSES 409 {@code NOT_RESIDUAL} with zero side effects; the operator must use
   * {@link #delete}, which runs the full P0 live/paper gate.
   *
   * <p>Auth + confirm are IDENTICAL to {@link #delete} (allowlisted operator, valid tenant id,
   * confirm-body match — none touch a store). On success: {@code TenantResidualCleanupRequested} →
   * exec broker_credentials delete (one call per known paper provider, idempotent) → bff
   * dashboard-rows delete LAST (bounded-retry, idempotent) → {@code
   * TenantResidualCleanupCompleted}, returning 200 {@code {status: CLEANED, deleted_stores}}. A
   * fully-clean tenant returns all-zero CLEANED (idempotent). A bff/exec fault after retries
   * returns the SAME 207 {@code stepFailed} shape as {@link #delete} so the operator can re-run the
   * idempotent tail.
   */
  @PostMapping("/{tenant}/cleanup-residual")
  public ResponseEntity<Map<String, Object>> cleanupResidual(
      HttpServletRequest req,
      @PathVariable("tenant") String tenant,
      @RequestBody(required = false) TenantDeleteRequestBody body) {

    String operator =
        ctx.requireAllowlistedOperator(req); // 400 if absent/malformed, 403 if not allowlisted
    String actor = "operator:" + operator;
    String correlationId = UUID.randomUUID().toString();

    if (!ctx.isValidTenantId(tenant)) {
      return blocked(HttpStatus.BAD_REQUEST, "INVALID_TENANT_ID", "malformed tenant path variable");
    }

    // Confirm match FIRST (cheap, no reads) — a typo never triggers a read or a side effect.
    String confirm = body == null ? null : body.confirmTenantId();
    if (confirm == null || !confirm.equals(tenant)) {
      return blocked(
          HttpStatus.BAD_REQUEST, "CONFIRM_MISMATCH", "confirm_tenant_id must equal path");
    }

    // ---- Residual precondition + paper-only guard (fail-closed). ONLY a tenant with ZERO
    // strategy_config rows is residual. A read fault fails closed (NOT_RESIDUAL) so an unreadable
    // store never lets cleanup proceed against a tenant that may still have live config. ----
    List<StrategyConfigReader.StrategyRow> rows;
    try {
      rows = reader.listByTenant(tenant);
    } catch (RuntimeException e) {
      return residualBlock(
          tenant,
          actor,
          correlationId,
          "NOT_RESIDUAL",
          "strategy_config read faulted (fail-closed)");
    }
    if (!rows.isEmpty()) {
      // The tenant still has config → not residual. It may be live/active/multi-strategy, so refuse
      // here and send the operator to the normal delete route which runs the full P0 live/paper
      // gate. ZERO side effects (no exec hop, no bff hop, no workflow, no disable).
      return residualBlock(
          tenant,
          actor,
          correlationId,
          "NOT_RESIDUAL",
          "tenant has "
              + rows.size()
              + " strategy_config row(s); use the delete route (runs the P0 gate)");
    }

    // ---- STRUCTURAL residual-only guard (fail-closed). Zero strategy_config rows is NOT proof the
    // tenant was created → P0-gated → torn down: a NEVER-created tenant also has zero rows (the
    // onboard invite step is independent of tenant creation, so an operator can invite a user for a
    // tenant_id before its config exists → dashboard rows + zero config → Phase 1 badges it
    // `partial`). Deleting that would strip a legitimate pending onboarding. So ALSO require audit
    // evidence that a delete was actually attempted: TenantDeleteRequested (emitted only after
    // P0–P3 pass) or TenantDeleteStepFailed. No evidence → refuse (a never-created tenant). A read
    // fault fails closed, same as the strategy_config read-fault path. ZERO side effects. ----
    try {
      if (!deleteHistory.deleteWasRequested(tenant)) {
        return residualBlock(
            tenant,
            actor,
            correlationId,
            "NEVER_DELETED",
            "no prior tenant-delete attempt for this tenant — refusing to touch data for a tenant"
                + " that was never deleted");
      }
    } catch (RuntimeException e) {
      return residualBlock(
          tenant,
          actor,
          correlationId,
          "NOT_RESIDUAL",
          "delete-history read faulted (fail-closed)");
    }

    // ---- Residual (rows == 0, prior delete attempted): delete ONLY the two idempotent residual
    // stores, same safe order as the delete route's tail (broker_credentials, then dashboard LAST).
    long startMillis = System.currentTimeMillis();

    audit.emit(
        "TenantResidualCleanupRequested",
        tenant,
        "*",
        actor,
        correlationId,
        residualRequestedSubject(tenant, confirm, operator));

    Map<String, Object> deletedStores = new LinkedHashMap<>();

    // 1. exec broker_credentials delete, one idempotent call per KNOWN paper provider (option b:
    // with
    // zero strategy rows the provider cannot be derived from the config, so iterate the closed
    // set).
    try {
      int credsDeleted = 0;
      for (String provider : KNOWN_PAPER_PROVIDERS) {
        credsDeleted += execCreds.delete(tenant, provider);
      }
      deletedStores.put("broker_credentials", credsDeleted);
    } catch (RuntimeException e) {
      return stepFailed(
          tenant,
          "*",
          actor,
          correlationId,
          "broker_credentials",
          new ArrayList<>(deletedStores.keySet()),
          e);
    }

    // 2. bff dashboard_user + dashboard_user_invite delete — LAST, bounded-retry, idempotent.
    try {
      DashboardRowsDeleteForwarder.DeletedCounts counts =
          deleteDashboardRowsWithRetry(tenant, operator);
      deletedStores.put("dashboard_user", counts.users());
      deletedStores.put("dashboard_user_invite", counts.invites());
    } catch (RuntimeException e) {
      return stepFailed(
          tenant,
          "*",
          actor,
          correlationId,
          "dashboard_user",
          new ArrayList<>(deletedStores.keySet()),
          e);
    }

    long durationMs = System.currentTimeMillis() - startMillis;
    Map<String, Object> completedSubject = new LinkedHashMap<>();
    completedSubject.put("deleted_stores", deletedStores);
    completedSubject.put("retained_stores", RETAINED_STORES);
    completedSubject.put("duration_ms", durationMs);
    audit.emit(
        "TenantResidualCleanupCompleted", tenant, "*", actor, correlationId, completedSubject);

    Map<String, Object> ok = new LinkedHashMap<>();
    ok.put("status", "CLEANED");
    ok.put("deleted_stores", deletedStores);
    ok.put("retained_stores", RETAINED_STORES);
    ok.put("duration_ms", durationMs);
    return ResponseEntity.ok(ok);
  }

  private static Map<String, Object> residualRequestedSubject(
      String tenant, String confirm, String operator) {
    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("tenant_id", tenant);
    subject.put("confirm_tenant_id", confirm);
    subject.put("operator_id", operator);
    subject.put("strategy_count", 0);
    subject.put("flag_state", "operator.tenant-delete.enabled=true");
    return subject;
  }

  /**
   * A residual-cleanup pre-flight refusal: records a {@code TenantDeleteBlocked} audit event then
   * returns 409. NO store was touched — the strategy_config / delete-history reads are read-only.
   * Deliberately REUSES the existing {@code TenantDeleteBlocked} audit kind (a refusal is a
   * refusal, same as a P0–P3 pre-flight block) rather than minting a new kind — only {@code
   * TenantResidualCleanup{Requested,Completed}} are new for this route. {@code blockedBy} is {@code
   * NEVER_DELETED} when the tenant has no prior-delete audit evidence (a never-created tenant that
   * only ever had an invite) so the operator banner distinguishes it from a still-configured tenant
   * ({@code NOT_RESIDUAL}); the more-specific reason still rides in {@code detail}.
   */
  private ResponseEntity<Map<String, Object>> residualBlock(
      String tenant, String actor, String correlationId, String blockedBy, String detail) {
    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("blocked_by", blockedBy);
    subject.put("detail", detail);
    subject.put("phase", "residual_precondition");
    audit.emit("TenantDeleteBlocked", tenant, "*", actor, correlationId, subject);
    return blocked(HttpStatus.CONFLICT, blockedBy, detail);
  }

  /**
   * The ONE shared "definitely a paper tenant" predicate, composed from the existing homes: {@link
   * StrategyConfigInvariants#isLive} (rejects {@code -live}) AND {@link
   * VerifiedAccountGuard#paperProvider} != null (matches {@code ^<provider>-paper$}). Never
   * hand-rolls {@code endsWith("-live")}.
   */
  static boolean isDefinitelyPaper(StrategyConfig cfg) {
    if (StrategyConfigInvariants.isLive(cfg)) {
      return false;
    }
    return VerifiedAccountGuard.paperProvider(brokerTargetOf(cfg)) != null;
  }

  private static String brokerTargetOf(StrategyConfig cfg) {
    return cfg.getBrokerTarget() == null ? null : cfg.getBrokerTarget().value();
  }

  /**
   * Distinct {@code <provider>} prefixes across the tenant's paper strategies (for the exec hop).
   */
  private static Set<String> distinctPaperProviders(List<StrategyConfigReader.StrategyRow> rows) {
    Set<String> providers = new LinkedHashSet<>();
    for (StrategyConfigReader.StrategyRow row : rows) {
      String provider = VerifiedAccountGuard.paperProvider(brokerTargetOf(row.config()));
      if (provider != null) {
        providers.add(provider);
      }
    }
    return providers;
  }

  private static Map<String, Object> requestedSubject(
      String tenant, String confirm, String operator, List<StrategyConfigReader.StrategyRow> rows) {
    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("tenant_id", tenant);
    subject.put("confirm_tenant_id", confirm);
    subject.put("operator_id", operator);
    subject.put("strategy_count", rows.size());
    subject.put("flag_state", "operator.tenant-delete.enabled=true");
    List<String> ids = new ArrayList<>();
    for (StrategyConfigReader.StrategyRow row : rows) {
      ids.add(row.strategyId());
    }
    subject.put("strategy_ids", ids);
    return subject;
  }

  private ResponseEntity<Map<String, Object>> stepFailed(
      String tenant,
      String strategy,
      String actor,
      String correlationId,
      String failedStep,
      List<String> completedSteps,
      RuntimeException cause) {
    log.error(
        "tenant-delete step failed tenant={} failed_step={} completed={} cause={}",
        tenant,
        failedStep,
        completedSteps,
        cause.getClass().getName());
    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("failed_step", failedStep);
    subject.put("completed_steps", completedSteps);
    subject.put("error", cause.getClass().getSimpleName());
    audit.emit("TenantDeleteStepFailed", tenant, strategy, actor, correlationId, subject);

    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("status", "STEP_FAILED");
    resp.put("completed_steps", completedSteps);
    resp.put("failed_step", failedStep);
    return ResponseEntity.status(HttpStatus.MULTI_STATUS).body(resp);
  }

  /**
   * A P0–P3 pre-flight refusal: records a {@code TenantDeleteBlocked} audit event (so an attempt to
   * delete a live/active/enabled/busy tenant is on the append-only trail) then returns 409. NO
   * teardown side effect ran — the guards are read-only.
   */
  private ResponseEntity<Map<String, Object>> preflightBlock(
      String tenant,
      String strategyForAudit,
      String actor,
      String correlationId,
      String blockedBy,
      String detail) {
    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("blocked_by", blockedBy);
    subject.put("detail", detail);
    subject.put("phase", "preflight");
    audit.emit("TenantDeleteBlocked", tenant, strategyForAudit, actor, correlationId, subject);
    return blocked(HttpStatus.CONFLICT, blockedBy, detail);
  }

  private static ResponseEntity<Map<String, Object>> blocked(
      HttpStatus status, String blockedBy, String detail) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("blocked_by", blockedBy);
    body.put("detail", detail);
    return ResponseEntity.status(status).body(body);
  }
}
