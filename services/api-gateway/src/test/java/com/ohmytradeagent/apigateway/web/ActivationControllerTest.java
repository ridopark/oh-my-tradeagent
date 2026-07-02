package com.ohmytradeagent.apigateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.LiveActivationRequest;
import com.ohmytradeagent.contract.LiveActivationResult;
import com.ohmytradeagent.contract.LiveDeactivationRequest;
import com.ohmytradeagent.orchestrator.workflows.LiveActivationWorkflow;
import com.ohmytradeagent.orchestrator.workflows.LiveDeactivationWorkflow;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowOptions;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phase F controller-slice test against a mocked {@link WorkflowClient} + typed blocking stubs.
 * Pins: missing X-Operator-Id → 400 (no workflow started); REJECTED_* → 422 with reason; ACTIVATED
 * → 200 (+expected_account_id); DEACTIVATED → 200; a WorkflowException (timeout / wedge) → 503; a
 * null outcome → 503. (The bearer-missing 401 is enforced by ServiceTokenFilter, covered in
 * ServiceTokenFilterTest; the flag-off 404 is bean-absence, covered structurally — the controller
 * bean only exists under operator.activation.enabled=true.)
 */
class ActivationControllerTest {

  private static final String TENANT = "dev";
  private static final String STRATEGY = "copytrade-v1";
  private static final String OPERATOR = "ridopark";

  private WorkflowClient workflowClient;
  private LiveActivationWorkflow activateStub;
  private LiveDeactivationWorkflow deactivateStub;
  private ActivationController controller;

  @BeforeEach
  void setUp() {
    workflowClient = mock(WorkflowClient.class);
    activateStub = mock(LiveActivationWorkflow.class);
    deactivateStub = mock(LiveDeactivationWorkflow.class);
    when(workflowClient.newWorkflowStub(
            eq(LiveActivationWorkflow.class), any(WorkflowOptions.class)))
        .thenReturn(activateStub);
    when(workflowClient.newWorkflowStub(
            eq(LiveDeactivationWorkflow.class), any(WorkflowOptions.class)))
        .thenReturn(deactivateStub);
    // Allowlist the OPERATOR so the outcome-mapping tests exercise the activate/deactivate paths;
    // the allowlist gate itself is covered by the dedicated 403 tests below.
    controller =
        new ActivationController(workflowClient, new TenantContext("dev", STRATEGY, OPERATOR));
  }

  private ActivationController controllerWithAllowlist(String allowlist) {
    return new ActivationController(workflowClient, new TenantContext("dev", STRATEGY, allowlist));
  }

  private static HttpServletRequest reqWithOperator(String operator) {
    MockHttpServletRequest req =
        new MockHttpServletRequest(
            "POST", "/admin/tenants/" + TENANT + "/strategies/" + STRATEGY + "/activate-live");
    if (operator != null) {
      req.addHeader("X-Operator-Id", operator);
    }
    return req;
  }

  private static LiveActivationResult result(
      LiveActivationResult.Outcome outcome, String reason, String accountId) {
    LiveActivationResult r = new LiveActivationResult();
    r.setSchemaVersion(1L);
    r.setOutcome(outcome);
    r.setReason(reason);
    r.setExpectedAccountId(accountId);
    return r;
  }

  // ---- auth ---------------------------------------------------------------------------------

  @Test
  void missingOperatorHeader_is400_noWorkflowStarted() {
    assertThatThrownBy(() -> controller.activate(reqWithOperator(null), TENANT, STRATEGY))
        .isInstanceOf(TenantContext.MissingHeaderException.class);
    verify(workflowClient, never()).newWorkflowStub(any(Class.class), any(WorkflowOptions.class));
  }

  @Test
  void nonAllowlistedOperator_activate_is403_noWorkflowStarted() {
    assertThatThrownBy(() -> controller.activate(reqWithOperator("intruder"), TENANT, STRATEGY))
        .isInstanceOf(TenantContext.UnauthorizedOperatorException.class);
    verify(workflowClient, never()).newWorkflowStub(any(Class.class), any(WorkflowOptions.class));
  }

  @Test
  void nonAllowlistedOperator_deactivate_is403_noWorkflowStarted() {
    assertThatThrownBy(() -> controller.deactivate(reqWithOperator("intruder"), TENANT, STRATEGY))
        .isInstanceOf(TenantContext.UnauthorizedOperatorException.class);
    verify(workflowClient, never()).newWorkflowStub(any(Class.class), any(WorkflowOptions.class));
  }

  @Test
  void emptyAllowlist_deniesAll_activate_is403() {
    ActivationController denyAll = controllerWithAllowlist(""); // empty = deny-all (fail-closed)
    assertThatThrownBy(() -> denyAll.activate(reqWithOperator(OPERATOR), TENANT, STRATEGY))
        .isInstanceOf(TenantContext.UnauthorizedOperatorException.class);
    verify(workflowClient, never()).newWorkflowStub(any(Class.class), any(WorkflowOptions.class));
  }

  // ---- activate outcome mapping -------------------------------------------------------------

  @Test
  void activated_returns200_withExpectedAccountId() {
    when(activateStub.activateLive(any(LiveActivationRequest.class)))
        .thenReturn(result(LiveActivationResult.Outcome.ACTIVATED, null, "PA3FKGPFYPLH"));

    var resp = controller.activate(reqWithOperator(OPERATOR), TENANT, STRATEGY);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(resp.getBody())
        .containsEntry("status", "ACTIVATED")
        .containsEntry("expected_account_id", "PA3FKGPFYPLH");
  }

  @Test
  void rejectedConfig_returns422_withReason() {
    when(activateStub.activateLive(any(LiveActivationRequest.class)))
        .thenReturn(
            result(
                LiveActivationResult.Outcome.REJECTED_CONFIG,
                "daily_loss_threshold must be set and > 0",
                null));

    var resp = controller.activate(reqWithOperator(OPERATOR), TENANT, STRATEGY);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(resp.getBody())
        .containsEntry("status", "REJECTED_CONFIG")
        .containsEntry("reason", "daily_loss_threshold must be set and > 0");
  }

  @Test
  void workflowFailure_returns503() {
    when(activateStub.activateLive(any(LiveActivationRequest.class)))
        .thenThrow(new TestWorkflowException());

    assertResponseStatus(
        () -> controller.activate(reqWithOperator(OPERATOR), TENANT, STRATEGY),
        HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  void nullOutcome_returns503() {
    when(activateStub.activateLive(any(LiveActivationRequest.class)))
        .thenReturn(result(null, null, null));

    assertResponseStatus(
        () -> controller.activate(reqWithOperator(OPERATOR), TENANT, STRATEGY),
        HttpStatus.SERVICE_UNAVAILABLE);
  }

  // ---- deactivate ---------------------------------------------------------------------------

  @Test
  void deactivated_returns200() {
    when(deactivateStub.deactivateLive(any(LiveDeactivationRequest.class)))
        .thenReturn(result(LiveActivationResult.Outcome.DEACTIVATED, null, null));

    var resp = controller.deactivate(reqWithOperator(OPERATOR), TENANT, STRATEGY);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(resp.getBody()).containsEntry("status", "DEACTIVATED");
  }

  @Test
  void deactivate_missingOperatorHeader_is400() {
    assertThatThrownBy(() -> controller.deactivate(reqWithOperator(null), TENANT, STRATEGY))
        .isInstanceOf(TenantContext.MissingHeaderException.class);
    verify(workflowClient, never()).newWorkflowStub(any(Class.class), any(WorkflowOptions.class));
  }

  private static final class TestWorkflowException extends WorkflowException {
    TestWorkflowException() {
      super("simulated workflow failure", WorkflowExecution.getDefaultInstance(), null, null);
    }
  }

  private static void assertResponseStatus(Runnable r, HttpStatus expected) {
    try {
      r.run();
      org.assertj.core.api.Assertions.fail("expected ResponseStatusException " + expected);
    } catch (ResponseStatusException ex) {
      assertThat(ex.getStatusCode()).isEqualTo(expected);
    }
  }
}
