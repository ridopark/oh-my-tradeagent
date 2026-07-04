package com.ohmytradeagent.apigateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
  void workflowThrows_207_stepFailed_execAndBffNeverCalled() {
    wireAllPass();
    // The teardown workflow faults (activity permanently failing / run timeout / WorkflowFailed).
    // A throw means NO COMPLETED result, so the exec/bff store deletes must never run.
    when(workflow.deleteTenant(eq(TENANT), any(), any(), any(), any()))
        .thenThrow(new RuntimeException("activity permanently failing"));

    ResponseEntity<Map<String, Object>> resp = call(TENANT);

    assertThat(resp.getStatusCode().is5xxServerError() || resp.getStatusCode().value() == 207)
        .isTrue();
    assertThat(resp.getBody()).containsEntry("failed_step", "tenant_delete_workflow");
    // The uncaught throw was converted into a clean audited response — never a COMPLETED path.
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
  void workflowReturnsBlocked_409_execAndBffNotCalled() {
    when(reader.listByTenant(TENANT))
        .thenReturn(List.of(row("copytrade-v1", "alpaca-paper", false)));
    when(liveActivation.isActive(eq(TENANT), any(), any())).thenReturn(false);
    when(openPositions.hasOpen(eq(TENANT), any())).thenReturn(false);
    when(workflow.deleteTenant(eq(TENANT), any(), any(), any(), any()))
        .thenReturn(TenantDeleteResult.blocked(TenantDeleteResult.BlockReason.BROKER_NOT_FLAT));

    ResponseEntity<Map<String, Object>> resp = call(TENANT);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(resp.getBody()).containsEntry("blocked_by", "BROKER_NOT_FLAT");
    // The downstream store deletes must NOT run once the teardown workflow refused.
    verifyNoInteractions(execCreds, dashboardRows);
    verify(audit).emit(eq("TenantDeleteBlocked"), eq(TENANT), any(), any(), any(), any());
  }

  @Test
  void bffHopThrows_207_stepFailed() {
    wireAllPass();
    when(dashboardRows.delete(eq(TENANT), eq(OPERATOR))).thenThrow(new RuntimeException("bff 502"));

    ResponseEntity<Map<String, Object>> resp = call(TENANT);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.MULTI_STATUS);
    assertThat(resp.getBody()).containsEntry("failed_step", "dashboard_user");
    assertThat(resp.getBody().get("completed_steps").toString()).contains("broker_credentials");
    verify(audit).emit(eq("TenantDeleteStepFailed"), eq(TENANT), any(), any(), any(), any());
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
}
