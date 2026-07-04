package com.ohmytradeagent.apigateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.workflows.TenantDeleteResult;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Phase 4 controller-slice tests against fully-mocked collaborators — the REAL-MONEY safety gate.
 * The load-bearing test is {@link #p0LiveBrokerTarget_409_zeroSideEffects()}: a {@code -live}
 * tenant is rejected {@code LIVE_BROKER_TARGET} with NO disable, NO teardown-workflow start, NO
 * exec hop, NO bff hop, and NO TenantDeleteRequested audit.
 */
class TenantDeleteControllerTest {

  private static final String TENANT = "staging-paper-2";
  private static final String OPERATOR = "ridopark@gmail.com";

  private StrategyConfigReader reader;
  private LiveActivationStateReader liveActivation;
  private OpenPositionWorkflowChecker openPositions;
  private StrategyDisableClient disable;
  private TenantDeleteWorkflowClient workflow;
  private BrokerCredentialDeleteForwarder execCreds;
  private DashboardRowsDeleteForwarder dashboardRows;
  private TenantDeleteAuditEmitter audit;
  private TenantDeleteController controller;

  @BeforeEach
  void setUp() {
    reader = mock(StrategyConfigReader.class);
    liveActivation = mock(LiveActivationStateReader.class);
    openPositions = mock(OpenPositionWorkflowChecker.class);
    disable = mock(StrategyDisableClient.class);
    workflow = mock(TenantDeleteWorkflowClient.class);
    execCreds = mock(BrokerCredentialDeleteForwarder.class);
    dashboardRows = mock(DashboardRowsDeleteForwarder.class);
    audit = mock(TenantDeleteAuditEmitter.class);
    controller = controllerWithAllowlist(OPERATOR);
  }

  private TenantDeleteController controllerWithAllowlist(String allowlist) {
    return new TenantDeleteController(
        new TenantContext("dev", "copytrade-v1", allowlist),
        reader,
        liveActivation,
        openPositions,
        disable,
        workflow,
        execCreds,
        dashboardRows,
        audit);
  }

  private static HttpServletRequest reqWithOperator(String operator) {
    MockHttpServletRequest req =
        new MockHttpServletRequest("POST", "/admin/tenants/" + TENANT + "/delete");
    if (operator != null) {
      req.addHeader("X-Operator-Id", operator);
    }
    return req;
  }

  private static TenantDeleteRequestBody confirm(String value) {
    return new TenantDeleteRequestBody(value);
  }

  private static StrategyConfigReader.StrategyRow row(
      String strategyId, String brokerTarget, boolean enabled) {
    StrategyConfig cfg = new StrategyConfig();
    if (brokerTarget != null) {
      cfg.setBrokerTarget(StrategyConfig.BrokerTarget.fromValue(brokerTarget));
    }
    cfg.setEnabled(enabled);
    return new StrategyConfigReader.StrategyRow(strategyId, cfg, 1L);
  }

  /** Wires all guards to pass for a single dark paper strategy. */
  private void wireAllPass() {
    when(reader.listByTenant(TENANT))
        .thenReturn(List.of(row("copytrade-v1", "alpaca-paper", false)));
    when(liveActivation.isActive(eq(TENANT), any(), any())).thenReturn(false);
    when(openPositions.hasOpen(eq(TENANT), any())).thenReturn(false);
    when(workflow.deleteTenant(eq(TENANT), any(), any(), any(), any()))
        .thenReturn(TenantDeleteResult.completed(1));
    when(execCreds.delete(eq(TENANT), any())).thenReturn(1);
    when(dashboardRows.delete(eq(TENANT), eq(OPERATOR)))
        .thenReturn(new DashboardRowsDeleteForwarder.DeletedCounts(0, 0));
    controller.setDashboardRetrySleeper(millis -> {}); // no real sleep in tests
  }

  private ResponseEntity<Map<String, Object>> call(String confirmValue) {
    return controller.delete(reqWithOperator(OPERATOR), TENANT, confirm(confirmValue));
  }

  // ---- THE CRITICAL TEST: a -live tenant → 409 LIVE_BROKER_TARGET, ZERO side effects ----

  @Test
  void p0LiveBrokerTarget_409_zeroSideEffects() {
    when(reader.listByTenant(TENANT))
        .thenReturn(List.of(row("copytrade-v1", "alpaca-live", false)));

    ResponseEntity<Map<String, Object>> resp = call(TENANT);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(resp.getBody()).containsEntry("blocked_by", "LIVE_BROKER_TARGET");
    // ZERO teardown side effects.
    verifyNoInteractions(disable, workflow, execCreds, dashboardRows);
    // NO TenantDeleteRequested audit (the delete never began).
    verify(audit, never()).emit(eq("TenantDeleteRequested"), any(), any(), any(), any(), any());
    // The refusal IS recorded on the append-only trail (safe, read-only).
    verify(audit).emit(eq("TenantDeleteBlocked"), eq(TENANT), any(), any(), any(), any());
  }

  @Test
  void p0LiveWinsOverNonPaper_whenMixed() {
    when(reader.listByTenant(TENANT))
        .thenReturn(List.of(row("s-bare", "live", false), row("s-live", "alpaca-live", false)));

    ResponseEntity<Map<String, Object>> resp = call(TENANT);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(resp.getBody()).containsEntry("blocked_by", "LIVE_BROKER_TARGET");
    verifyNoInteractions(disable, workflow, execCreds, dashboardRows);
  }

  // ---- MULTI_STRATEGY_UNSUPPORTED pre-flight (single-strategy scope only) ----

  @Test
  void multiStrategyTenant_409_unsupported_zeroSideEffects() {
    // Two all-paper strategy rows → rejected at pre-flight BEFORE any teardown, so the
    // partial-teardown path (strategy A torn down while strategy B evaluates P4/P5) is unreachable.
    when(reader.listByTenant(TENANT))
        .thenReturn(
            List.of(
                row("copytrade-v1", "alpaca-paper", false),
                row("copytrade-v2", "alpaca-paper", false)));

    ResponseEntity<Map<String, Object>> resp = call(TENANT);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(resp.getBody()).containsEntry("blocked_by", "MULTI_STRATEGY_UNSUPPORTED");
    // ZERO teardown side effects — no disable, no workflow, no exec hop, no bff hop.
    verifyNoInteractions(disable, workflow, execCreds, dashboardRows);
    // Only the Blocked audit — NOT Requested, NOT Completed (the delete never began).
    verify(audit).emit(eq("TenantDeleteBlocked"), eq(TENANT), any(), any(), any(), any());
    verify(audit, never()).emit(eq("TenantDeleteRequested"), any(), any(), any(), any(), any());
    verify(audit, never()).emit(eq("TenantDeleteCompleted"), any(), any(), any(), any(), any());
  }

  @Test
  void multiStrategyTenant_withLiveRow_409_liveWins() {
    // A 2-row tenant where one routes -live must report LIVE_BROKER_TARGET (P0), NOT
    // MULTI_STRATEGY_UNSUPPORTED — proves P0 is still evaluated before the multi-strategy guard.
    when(reader.listByTenant(TENANT))
        .thenReturn(
            List.of(
                row("copytrade-v1", "alpaca-paper", false),
                row("copytrade-v2", "alpaca-live", false)));

    ResponseEntity<Map<String, Object>> resp = call(TENANT);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(resp.getBody()).containsEntry("blocked_by", "LIVE_BROKER_TARGET");
    verifyNoInteractions(disable, workflow, execCreds, dashboardRows);
  }

  // ---- P0 shape variants ----

  @Test
  void p0_zeroRows_409_unknownTenantShape() {
    when(reader.listByTenant(TENANT)).thenReturn(List.of());
    ResponseEntity<Map<String, Object>> resp = call(TENANT);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(resp.getBody()).containsEntry("blocked_by", "UNKNOWN_TENANT_SHAPE");
    verifyNoInteractions(disable, workflow, execCreds, dashboardRows);
  }

  @Test
  void p0_nullBrokerTarget_409_nonPaper() {
    when(reader.listByTenant(TENANT)).thenReturn(List.of(row("s1", null, false)));
    assertThat(call(TENANT).getBody()).containsEntry("blocked_by", "NON_PAPER_BROKER_TARGET");
    verifyNoInteractions(disable, workflow, execCreds, dashboardRows);
  }

  @Test
  void p0_bareLiveTarget_409_nonPaper() {
    when(reader.listByTenant(TENANT)).thenReturn(List.of(row("s1", "live", false)));
    assertThat(call(TENANT).getBody()).containsEntry("blocked_by", "NON_PAPER_BROKER_TARGET");
    verifyNoInteractions(disable, workflow, execCreds, dashboardRows);
  }

  @Test
  void p0_bareTarget_409_nonPaper() {
    // "paper" (bare, no provider) is not a <provider>-paper target → not definitely-paper.
    when(reader.listByTenant(TENANT)).thenReturn(List.of(row("s1", "paper", false)));
    assertThat(call(TENANT).getBody()).containsEntry("blocked_by", "NON_PAPER_BROKER_TARGET");
    verifyNoInteractions(disable, workflow, execCreds, dashboardRows);
  }

  @Test
  void p0_unparseableRow_409_failClosed_notSkipped() {
    // The reader throws on an unparseable row; the guard must fail closed (409), never skip it.
    when(reader.listByTenant(TENANT)).thenThrow(new IllegalStateException("unparseable config"));
    ResponseEntity<Map<String, Object>> resp = call(TENANT);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(resp.getBody()).containsEntry("blocked_by", "NON_PAPER_BROKER_TARGET");
    verifyNoInteractions(disable, workflow, execCreds, dashboardRows);
  }

  // ---- P1/P2/P3 ordering + fail-closed ----

  @Test
  void p1_activeLivePromotion_409() {
    when(reader.listByTenant(TENANT))
        .thenReturn(List.of(row("copytrade-v1", "alpaca-paper", false)));
    when(liveActivation.isActive(eq(TENANT), any(), any())).thenReturn(true);
    assertThat(call(TENANT).getBody()).containsEntry("blocked_by", "ACTIVE_LIVE_ACTIVATION");
    verifyNoInteractions(disable, workflow, execCreds, dashboardRows);
  }

  @Test
  void p1_readFault_409_failClosed() {
    when(reader.listByTenant(TENANT))
        .thenReturn(List.of(row("copytrade-v1", "alpaca-paper", false)));
    when(liveActivation.isActive(eq(TENANT), any(), any()))
        .thenThrow(new RuntimeException("audit_log down"));
    assertThat(call(TENANT).getBody()).containsEntry("blocked_by", "ACTIVE_LIVE_ACTIVATION");
    verifyNoInteractions(disable, workflow, execCreds, dashboardRows);
  }

  @Test
  void p2_strategyEnabled_409() {
    when(reader.listByTenant(TENANT))
        .thenReturn(List.of(row("copytrade-v1", "alpaca-paper", true))); // enabled=true
    when(liveActivation.isActive(eq(TENANT), any(), any())).thenReturn(false);
    assertThat(call(TENANT).getBody()).containsEntry("blocked_by", "STRATEGY_ENABLED");
    verifyNoInteractions(disable, workflow, execCreds, dashboardRows);
  }

  @Test
  void p3_openWorkflows_409() {
    when(reader.listByTenant(TENANT))
        .thenReturn(List.of(row("copytrade-v1", "alpaca-paper", false)));
    when(liveActivation.isActive(eq(TENANT), any(), any())).thenReturn(false);
    when(openPositions.hasOpen(eq(TENANT), any())).thenReturn(true);
    assertThat(call(TENANT).getBody()).containsEntry("blocked_by", "OPEN_WORKFLOWS");
    verifyNoInteractions(disable, workflow, execCreds, dashboardRows);
  }

  @Test
  void p3_readFault_409_failClosed() {
    when(reader.listByTenant(TENANT))
        .thenReturn(List.of(row("copytrade-v1", "alpaca-paper", false)));
    when(liveActivation.isActive(eq(TENANT), any(), any())).thenReturn(false);
    when(openPositions.hasOpen(eq(TENANT), any()))
        .thenThrow(new RuntimeException("visibility down"));
    assertThat(call(TENANT).getBody()).containsEntry("blocked_by", "OPEN_WORKFLOWS");
    verifyNoInteractions(disable, workflow, execCreds, dashboardRows);
  }

  @Test
  void p0BeforeP1_firstFailureWins() {
    // A -live tenant must report the P0 blocker; P0 short-circuits before P1 is even evaluated.
    when(reader.listByTenant(TENANT))
        .thenReturn(List.of(row("copytrade-v1", "alpaca-live", false)));
    assertThat(call(TENANT).getBody()).containsEntry("blocked_by", "LIVE_BROKER_TARGET");
    verify(liveActivation, never()).isActive(any(), any(), any());
    verifyNoInteractions(disable, workflow, execCreds, dashboardRows);
  }

  // ---- confirm ----

  @Test
  void confirmMismatch_400_noSideEffects() {
    ResponseEntity<Map<String, Object>> resp = call("WRONG-TENANT");
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(resp.getBody()).containsEntry("blocked_by", "CONFIRM_MISMATCH");
    // Confirm is checked before any read or side effect.
    verifyNoInteractions(
        reader, liveActivation, openPositions, disable, workflow, execCreds, dashboardRows, audit);
  }

  // ---- L4: malformed {tenant} path var → 400, plain reject ----

  @Test
  void malformedTenant_400_noReadsNoSideEffects() {
    // A malformed tenant path var (illegal charset) is a plain 400 — no guard read, no side effect,
    // no audit. confirm matches the (malformed) path so the reject is provably the tenant guard.
    ResponseEntity<Map<String, Object>> resp =
        controller.delete(reqWithOperator(OPERATOR), "bad tenant!", confirm("bad tenant!"));
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(resp.getBody()).containsEntry("blocked_by", "INVALID_TENANT_ID");
    verifyNoInteractions(
        reader, liveActivation, openPositions, disable, workflow, execCreds, dashboardRows, audit);
  }

  @Test
  void emptyTenant_400_noReadsNoSideEffects() {
    ResponseEntity<Map<String, Object>> resp =
        controller.delete(reqWithOperator(OPERATOR), "", confirm(""));
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(resp.getBody()).containsEntry("blocked_by", "INVALID_TENANT_ID");
    verifyNoInteractions(
        reader, liveActivation, openPositions, disable, workflow, execCreds, dashboardRows, audit);
  }

  // ---- L3: teardown-workflow fault → clean audited response, downstream NEVER called ----

  @Test
  void workflowThrows_207_stepFailed_execAndDashboardNeverCalled() {
    wireAllPass();
    // The teardown workflow faults (activity permanently failing / run timeout / WorkflowFailed).
    // A throw means NO COMPLETED result, so NEITHER the exec broker_credentials delete NOR the
    // (dashboard-LAST) bff hop must run. completed_steps carries only strategy_config? No — the
    // workflow never COMPLETED, so nothing downstream ran and completed_steps is EMPTY.
    when(workflow.deleteTenant(eq(TENANT), any(), any(), any(), any()))
        .thenThrow(new RuntimeException("activity permanently failing"));

    ResponseEntity<Map<String, Object>> resp = call(TENANT);

    assertThat(resp.getStatusCode().is5xxServerError() || resp.getStatusCode().value() == 207)
        .isTrue();
    assertThat(resp.getBody()).containsEntry("failed_step", "tenant_delete_workflow");
    assertThat(resp.getBody().get("completed_steps").toString())
        .doesNotContain("broker_credentials")
        .doesNotContain("dashboard_user");
    // The uncaught throw was converted into a clean audited response — never a COMPLETED path. The
    // exec broker_credentials delete AND the dashboard bff hop (both AFTER the workflow) never ran.
    verifyNoInteractions(execCreds, dashboardRows);
    verify(audit).emit(eq("TenantDeleteStepFailed"), eq(TENANT), any(), any(), any(), any());
    verify(audit, never()).emit(eq("TenantDeleteCompleted"), any(), any(), any(), any(), any());
  }

  @Test
  void disableThrows_207_stepFailed_noWorkflowNoExecNoBff() {
    wireAllPass();
    // The disarm-first disable step faults mid-flight. It must yield an audited
    // TenantDeleteStepFailed
    // + clean response (never a raw 500), and NO teardown (workflow/exec/bff) may run.
    doThrow(new RuntimeException("disable failed"))
        .when(disable)
        .disable(eq(TENANT), any(), any(), any());

    ResponseEntity<Map<String, Object>> resp = call(TENANT);

    assertThat(resp.getStatusCode().is5xxServerError() || resp.getStatusCode().value() == 207)
        .isTrue();
    assertThat(resp.getBody()).containsEntry("failed_step", "disable");
    // Disable is before any teardown — nothing downstream ran.
    verifyNoInteractions(workflow, execCreds, dashboardRows);
    verify(audit).emit(eq("TenantDeleteStepFailed"), eq(TENANT), any(), any(), any(), any());
    verify(audit, never()).emit(eq("TenantDeleteCompleted"), any(), any(), any(), any(), any());
  }

  // ---- happy path ----

  @Test
  void happyPath_orchestratesInOrder_200() {
    wireAllPass();

    ResponseEntity<Map<String, Object>> resp = call(TENANT);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(resp.getBody()).containsEntry("status", "DELETED");
    // Order: Requested → disable → teardown workflow (strategy_config, first irreversible, runs the
    // P4/P5 gates) → broker_credentials → dashboard_user (over-the-network, reversible, torn down
    // LAST so a P4/P5-BLOCKED or workflow-faulted delete never touches dashboard members).
    InOrder ordered = inOrder(audit, disable, workflow, execCreds, dashboardRows);
    ordered.verify(audit).emit(eq("TenantDeleteRequested"), eq(TENANT), any(), any(), any(), any());
    ordered.verify(disable).disable(eq(TENANT), eq("copytrade-v1"), any(), any());
    ordered
        .verify(workflow)
        .deleteTenant(eq(TENANT), eq("copytrade-v1"), eq("alpaca-paper"), any(), any());
    ordered.verify(execCreds).delete(TENANT, "alpaca");
    ordered.verify(dashboardRows).delete(TENANT, OPERATOR);
    ordered.verify(audit).emit(eq("TenantDeleteCompleted"), eq(TENANT), any(), any(), any(), any());
  }

  @Test
  void workflowReturnsBlocked_409_execAndDashboardNotCalled() {
    // LOAD-BEARING REGRESSION TEST (locks the fix). A tenant that legitimately BLOCKS on P4
    // (BROKER_NOT_FLAT) or P5 (HAS_TRADE_HISTORY) — checked ONLY inside the teardown workflow —
    // must return 409 WITHOUT any downstream side effect. Critically, the bff dashboard_user /
    // dashboard_user_invite delete must NOT run: the tenant SURVIVES the blocked delete (its
    // strategy_config and kill switches are intact), so hard-deleting its dashboard members would
    // irreversibly strip dashboard access from a fully-alive tenant. dashboard-first (commit
    // 2ad1380) had this bug; dashboard-LAST makes it structurally impossible.
    when(reader.listByTenant(TENANT))
        .thenReturn(List.of(row("copytrade-v1", "alpaca-paper", false)));
    when(liveActivation.isActive(eq(TENANT), any(), any())).thenReturn(false);
    when(openPositions.hasOpen(eq(TENANT), any())).thenReturn(false);
    when(workflow.deleteTenant(eq(TENANT), any(), any(), any(), any()))
        .thenReturn(TenantDeleteResult.blocked(TenantDeleteResult.BlockReason.BROKER_NOT_FLAT));

    ResponseEntity<Map<String, Object>> resp = call(TENANT);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(resp.getBody()).containsEntry("blocked_by", "BROKER_NOT_FLAT");
    // Neither the exec broker_credentials delete NOR the bff dashboard-rows delete may run once the
    // teardown workflow refused — the tenant survives fully intact, dashboard members untouched.
    verifyNoInteractions(execCreds, dashboardRows);
    verify(audit).emit(eq("TenantDeleteBlocked"), eq(TENANT), any(), any(), any(), any());
    verify(audit, never()).emit(eq("TenantDeleteCompleted"), any(), any(), any(), any(), any());
  }

  @Test
  void bffHopExhaustsRetries_207_stepFailed_deletedStoresPopulated() {
    // Dashboard-LAST resilience guard (the now-rare residual). The bff is the only over-the-network
    // store; if it is genuinely OUTAGE-down through all retry attempts, the delete fails LOUD with
    // a
    // 207 + TenantDeleteStepFailed carrying the ALREADY-COMPLETED irreversible stores
    // (strategy_config, broker_credentials) so the operator can re-run the idempotent
    // dashboard-rows
    // delete to converge. Every attempt throws → retries exhausted.
    wireAllPass();
    when(dashboardRows.delete(eq(TENANT), eq(OPERATOR)))
        .thenThrow(new RuntimeException("ResourceAccessException: connection refused"));

    ResponseEntity<Map<String, Object>> resp = call(TENANT);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.MULTI_STATUS);
    assertThat(resp.getBody()).containsEntry("failed_step", "dashboard_user");
    // The irreversible stores DID complete before the (last) store faulted — completed_steps
    // carries them so a re-run knows exactly what remains (the idempotent dashboard-rows delete).
    assertThat(resp.getBody().get("completed_steps").toString())
        .contains("strategy_config")
        .contains("broker_credentials");
    // Bounded retry was actually attempted (default 3 attempts) before giving up.
    verify(dashboardRows, times(TenantDeleteController.DASHBOARD_DELETE_MAX_ATTEMPTS))
        .delete(TENANT, OPERATOR);
    verify(audit).emit(eq("TenantDeleteStepFailed"), eq(TENANT), any(), any(), any(), any());
    verify(audit, never()).emit(eq("TenantDeleteCompleted"), any(), any(), any(), any(), any());
  }

  @Test
  void bffHopTransientThenSuccess_retriesAndConverges_200() {
    // A transient bff fault (idempotent DELETE) recovers within the bounded retry → the delete
    // converges to 200 DELETED, dashboard rows torn down. Proves retry is safe + effective.
    wireAllPass();
    when(dashboardRows.delete(eq(TENANT), eq(OPERATOR)))
        .thenThrow(new RuntimeException("ResourceAccessException: connection refused"))
        .thenReturn(new DashboardRowsDeleteForwarder.DeletedCounts(1, 0));

    ResponseEntity<Map<String, Object>> resp = call(TENANT);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(resp.getBody()).containsEntry("status", "DELETED");
    verify(dashboardRows, times(2)).delete(TENANT, OPERATOR); // one fault + one success
    verify(audit).emit(eq("TenantDeleteCompleted"), eq(TENANT), any(), any(), any(), any());
  }

  // ---- auth ----

  @Test
  void missingOperator_400_noReadsNoSideEffects() {
    assertThatThrownBy(() -> controller.delete(reqWithOperator(null), TENANT, confirm(TENANT)))
        .isInstanceOf(TenantContext.MissingHeaderException.class);
    verifyNoInteractions(reader, disable, workflow, execCreds, dashboardRows, audit);
  }

  @Test
  void nonAllowlistedOperator_403_noReadsNoSideEffects() {
    assertThatThrownBy(
            () -> controller.delete(reqWithOperator("intruder@evil.com"), TENANT, confirm(TENANT)))
        .isInstanceOf(TenantContext.UnauthorizedOperatorException.class);
    verifyNoInteractions(reader, disable, workflow, execCreds, dashboardRows, audit);
  }

  @Test
  void emptyAllowlist_deniesAll_403() {
    TenantDeleteController denyAll = controllerWithAllowlist(""); // fail-closed
    assertThatThrownBy(() -> denyAll.delete(reqWithOperator(OPERATOR), TENANT, confirm(TENANT)))
        .isInstanceOf(TenantContext.UnauthorizedOperatorException.class);
    verifyNoInteractions(reader, disable, workflow, execCreds, dashboardRows, audit);
  }

  // ---- Phase 2: residual-cleanup route (converges a partially-deleted tenant) ----

  private ResponseEntity<Map<String, Object>> cleanup(String confirmValue) {
    return controller.cleanupResidual(reqWithOperator(OPERATOR), TENANT, confirm(confirmValue));
  }

  @Test
  void cleanupResidual_zeroStrategyConfig_deletesResiduals_200() {
    // strategy_config already gone (residual) but broker_credentials + dashboard rows remain.
    when(reader.listByTenant(TENANT)).thenReturn(List.of());
    when(execCreds.delete(eq(TENANT), any())).thenReturn(1);
    when(dashboardRows.delete(eq(TENANT), eq(OPERATOR)))
        .thenReturn(new DashboardRowsDeleteForwarder.DeletedCounts(2, 1));
    controller.setDashboardRetrySleeper(millis -> {});

    ResponseEntity<Map<String, Object>> resp = cleanup(TENANT);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(resp.getBody()).containsEntry("status", "CLEANED");
    @SuppressWarnings("unchecked")
    Map<String, Object> stores = (Map<String, Object>) resp.getBody().get("deleted_stores");
    assertThat(stores)
        .containsEntry("broker_credentials", 1)
        .containsEntry("dashboard_user", 2)
        .containsEntry("dashboard_user_invite", 1);
    // Residual-only: NEVER a workflow or a disable — only the two idempotent residual stores.
    verifyNoInteractions(workflow, disable);
    // exec called once per known paper provider; bff called (dashboard-LAST).
    verify(execCreds).delete(TENANT, "alpaca");
    verify(dashboardRows).delete(TENANT, OPERATOR);
    verify(audit)
        .emit(eq("TenantResidualCleanupRequested"), eq(TENANT), any(), any(), any(), any());
    verify(audit)
        .emit(eq("TenantResidualCleanupCompleted"), eq(TENANT), any(), any(), any(), any());
  }

  @Test
  void cleanupResidual_idempotent_allZero_200() {
    // A fully-clean residual tenant: both stores already empty → 200 CLEANED, all-zero.
    when(reader.listByTenant(TENANT)).thenReturn(List.of());
    when(execCreds.delete(eq(TENANT), any())).thenReturn(0);
    when(dashboardRows.delete(eq(TENANT), eq(OPERATOR)))
        .thenReturn(new DashboardRowsDeleteForwarder.DeletedCounts(0, 0));
    controller.setDashboardRetrySleeper(millis -> {});

    ResponseEntity<Map<String, Object>> resp = cleanup(TENANT);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(resp.getBody()).containsEntry("status", "CLEANED");
    @SuppressWarnings("unchecked")
    Map<String, Object> stores = (Map<String, Object>) resp.getBody().get("deleted_stores");
    assertThat(stores)
        .containsEntry("broker_credentials", 0)
        .containsEntry("dashboard_user", 0)
        .containsEntry("dashboard_user_invite", 0);
    verify(audit)
        .emit(eq("TenantResidualCleanupCompleted"), eq(TENANT), any(), any(), any(), any());
  }

  @Test
  void cleanupResidual_strategyConfigStillPresent_409_notResidual() {
    // ≥1 strategy_config row → NOT residual. Refuse 409 NOT_RESIDUAL, ZERO side effects — the
    // operator must use the normal delete route (which runs the full P0 live/paper gate).
    when(reader.listByTenant(TENANT))
        .thenReturn(List.of(row("copytrade-v1", "alpaca-paper", false)));

    ResponseEntity<Map<String, Object>> resp = cleanup(TENANT);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(resp.getBody()).containsEntry("blocked_by", "NOT_RESIDUAL");
    verifyNoInteractions(execCreds, dashboardRows, workflow, disable);
    verify(audit).emit(eq("TenantDeleteBlocked"), eq(TENANT), any(), any(), any(), any());
    verify(audit, never())
        .emit(eq("TenantResidualCleanupCompleted"), any(), any(), any(), any(), any());
  }

  @Test
  void cleanupResidual_bffHopExhaustsRetries_207_stepFailed() {
    // broker_credentials cleaned, but the bff dashboard-rows delete is OUTAGE-down through all
    // retry
    // attempts → 207 TenantDeleteStepFailed carrying the completed broker_credentials store so the
    // operator can re-run the idempotent dashboard-rows delete. Reuses the delete route's retry
    // seam.
    when(reader.listByTenant(TENANT)).thenReturn(List.of());
    when(execCreds.delete(eq(TENANT), any())).thenReturn(1);
    when(dashboardRows.delete(eq(TENANT), eq(OPERATOR)))
        .thenThrow(new RuntimeException("ResourceAccessException: connection refused"));
    controller.setDashboardRetrySleeper(millis -> {});

    ResponseEntity<Map<String, Object>> resp = cleanup(TENANT);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.MULTI_STATUS);
    assertThat(resp.getBody()).containsEntry("failed_step", "dashboard_user");
    assertThat(resp.getBody().get("completed_steps").toString()).contains("broker_credentials");
    verify(dashboardRows, times(TenantDeleteController.DASHBOARD_DELETE_MAX_ATTEMPTS))
        .delete(TENANT, OPERATOR);
    verify(audit).emit(eq("TenantDeleteStepFailed"), eq(TENANT), any(), any(), any(), any());
    verify(audit, never())
        .emit(eq("TenantResidualCleanupCompleted"), any(), any(), any(), any(), any());
  }

  @Test
  void cleanupResidual_confirmMismatch_400_noSideEffects() {
    ResponseEntity<Map<String, Object>> resp = cleanup("WRONG-TENANT");
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(resp.getBody()).containsEntry("blocked_by", "CONFIRM_MISMATCH");
    // Confirm is checked before any read or side effect.
    verifyNoInteractions(
        reader, liveActivation, openPositions, disable, workflow, execCreds, dashboardRows, audit);
  }

  @Test
  void cleanupResidual_missingOperator_400() {
    assertThatThrownBy(
            () -> controller.cleanupResidual(reqWithOperator(null), TENANT, confirm(TENANT)))
        .isInstanceOf(TenantContext.MissingHeaderException.class);
    verifyNoInteractions(reader, disable, workflow, execCreds, dashboardRows, audit);
  }

  @Test
  void cleanupResidual_nonAllowlistedOperator_403() {
    assertThatThrownBy(
            () ->
                controller.cleanupResidual(
                    reqWithOperator("intruder@evil.com"), TENANT, confirm(TENANT)))
        .isInstanceOf(TenantContext.UnauthorizedOperatorException.class);
    verifyNoInteractions(reader, disable, workflow, execCreds, dashboardRows, audit);
  }
}
