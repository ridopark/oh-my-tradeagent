package com.ohmytradeagent.apigateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.StrategyConfigUpdateRequest;
import com.ohmytradeagent.contract.StrategyConfigUpdateResult;
import com.ohmytradeagent.orchestrator.workflows.StrategyConfigUpdateWorkflow;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowOptions;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * A1 operator enable route slice test against a mocked {@link WorkflowClient} + typed blocking
 * stub, {@link StrategyConfigReader}, and {@link VerifiedAccountGuard}. Pins: a verified account →
 * the update workflow starts with enabled=true (2xx); no verified account → 422, workflow NOT
 * started; a guard fault → 503; an unsupported (live) target → 422; no stored row → 404;
 * non-allowlisted operator → 403; absent X-Operator-Id → 400. In every non-ALLOW case the workflow
 * is NEVER started.
 */
class OperatorStrategyEnableControllerTest {

  private static final String TENANT = "acme";
  private static final String STRATEGY = "copytrade-v1";
  private static final String OPERATOR = "ridopark@gmail.com";

  private WorkflowClient workflowClient;
  private StrategyConfigUpdateWorkflow stub;
  private StrategyConfigReader reader;
  private VerifiedAccountGuard guard;
  private OperatorStrategyEnableController controller;

  @BeforeEach
  void setUp() {
    workflowClient = mock(WorkflowClient.class);
    stub = mock(StrategyConfigUpdateWorkflow.class);
    reader = mock(StrategyConfigReader.class);
    guard = mock(VerifiedAccountGuard.class);
    when(workflowClient.newWorkflowStub(
            eq(StrategyConfigUpdateWorkflow.class), any(WorkflowOptions.class)))
        .thenReturn(stub);
    controller =
        new OperatorStrategyEnableController(
            workflowClient, new TenantContext("dev", STRATEGY, OPERATOR), reader, guard);
  }

  private OperatorStrategyEnableController controllerWithAllowlist(String allowlist) {
    return new OperatorStrategyEnableController(
        workflowClient, new TenantContext("dev", STRATEGY, allowlist), reader, guard);
  }

  private static HttpServletRequest reqWithOperator(String operator) {
    MockHttpServletRequest req =
        new MockHttpServletRequest(
            "POST", "/admin/tenants/" + TENANT + "/strategies/" + STRATEGY + "/enable");
    if (operator != null) {
      req.addHeader("X-Operator-Id", operator);
    }
    return req;
  }

  private void stubStoredDisabled() {
    StrategyConfig stored = new StrategyConfig();
    stored.setEnabled(false);
    stored.setBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_PAPER);
    when(reader.read(TENANT, STRATEGY))
        .thenReturn(Optional.of(new StrategyConfigReader.Stored(stored, 3L)));
  }

  private static StrategyConfigUpdateResult resultOf(
      StrategyConfigUpdateResult.Outcome outcome, Long newVersion) {
    StrategyConfigUpdateResult r = new StrategyConfigUpdateResult();
    r.setSchemaVersion(1L);
    r.setOutcome(outcome);
    r.setNewVersion(newVersion);
    return r;
  }

  @Test
  void verifiedAccount_startsUpdateWorkflow_withEnabledTrue_andExpectedVersion() {
    stubStoredDisabled();
    when(guard.evaluate(eq(TENANT), eq("alpaca-paper")))
        .thenReturn(VerifiedAccountGuard.Decision.ALLOW);
    when(stub.update(any(StrategyConfigUpdateRequest.class)))
        .thenReturn(resultOf(StrategyConfigUpdateResult.Outcome.UPDATED, 4L));

    var resp = controller.enable(reqWithOperator(OPERATOR), TENANT, STRATEGY, null);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(resp.getBody()).containsEntry("status", "UPDATED").containsEntry("new_version", 4L);

    ArgumentCaptor<StrategyConfigUpdateRequest> captor =
        ArgumentCaptor.forClass(StrategyConfigUpdateRequest.class);
    verify(stub).update(captor.capture());
    StrategyConfigUpdateRequest req = captor.getValue();
    assertThat(req.getTenantId()).isEqualTo(TENANT);
    assertThat(req.getStrategyId()).isEqualTo(STRATEGY);
    assertThat(req.getExpectedVersion()).isEqualTo(3L);
    assertThat(req.getConfig().getEnabled()).isTrue();
    assertThat(req.getActor()).contains(OPERATOR);
  }

  @Test
  void noVerifiedAccount_is422_noWorkflowStarted() {
    stubStoredDisabled();
    when(guard.evaluate(eq(TENANT), eq("alpaca-paper")))
        .thenReturn(VerifiedAccountGuard.Decision.REJECT_UNVERIFIED);

    assertResponseStatus(
        () -> controller.enable(reqWithOperator(OPERATOR), TENANT, STRATEGY, null),
        HttpStatus.UNPROCESSABLE_ENTITY);
    verify(workflowClient, never()).newWorkflowStub(any(Class.class), any(WorkflowOptions.class));
  }

  @Test
  void guardFault_is503_noWorkflowStarted() {
    stubStoredDisabled();
    when(guard.evaluate(eq(TENANT), eq("alpaca-paper")))
        .thenReturn(VerifiedAccountGuard.Decision.FAULT);

    assertResponseStatus(
        () -> controller.enable(reqWithOperator(OPERATOR), TENANT, STRATEGY, null),
        HttpStatus.SERVICE_UNAVAILABLE);
    verify(workflowClient, never()).newWorkflowStub(any(Class.class), any(WorkflowOptions.class));
  }

  @Test
  void unsupportedLiveTarget_is422_noWorkflowStarted() {
    StrategyConfig stored = new StrategyConfig();
    stored.setEnabled(false);
    stored.setBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_LIVE);
    when(reader.read(TENANT, STRATEGY))
        .thenReturn(Optional.of(new StrategyConfigReader.Stored(stored, 3L)));
    when(guard.evaluate(eq(TENANT), eq("alpaca-live")))
        .thenReturn(VerifiedAccountGuard.Decision.REJECT_UNSUPPORTED_TARGET);

    assertResponseStatus(
        () -> controller.enable(reqWithOperator(OPERATOR), TENANT, STRATEGY, null),
        HttpStatus.UNPROCESSABLE_ENTITY);
    verify(workflowClient, never()).newWorkflowStub(any(Class.class), any(WorkflowOptions.class));
  }

  @Test
  void noStoredRow_is404_noGuardNoWorkflow() {
    when(reader.read(TENANT, STRATEGY)).thenReturn(Optional.empty());

    var resp = controller.enable(reqWithOperator(OPERATOR), TENANT, STRATEGY, null);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    org.mockito.Mockito.verifyNoInteractions(guard);
    verify(workflowClient, never()).newWorkflowStub(any(Class.class), any(WorkflowOptions.class));
  }

  @Test
  void missingOperatorHeader_is400_noReadNoWorkflow() {
    assertThatThrownBy(() -> controller.enable(reqWithOperator(null), TENANT, STRATEGY, null))
        .isInstanceOf(TenantContext.MissingHeaderException.class);
    org.mockito.Mockito.verifyNoInteractions(reader, guard);
    verify(workflowClient, never()).newWorkflowStub(any(Class.class), any(WorkflowOptions.class));
  }

  @Test
  void nonAllowlistedOperator_is403_noReadNoWorkflow() {
    OperatorStrategyEnableController denyAll = controllerWithAllowlist("");
    assertThatThrownBy(() -> denyAll.enable(reqWithOperator(OPERATOR), TENANT, STRATEGY, null))
        .isInstanceOf(TenantContext.UnauthorizedOperatorException.class);
    org.mockito.Mockito.verifyNoInteractions(reader, guard);
    verify(workflowClient, never()).newWorkflowStub(any(Class.class), any(WorkflowOptions.class));
  }

  @Test
  void workflowFailure_is503() {
    stubStoredDisabled();
    when(guard.evaluate(eq(TENANT), eq("alpaca-paper")))
        .thenReturn(VerifiedAccountGuard.Decision.ALLOW);
    when(stub.update(any(StrategyConfigUpdateRequest.class)))
        .thenThrow(new TestWorkflowException());

    assertResponseStatus(
        () -> controller.enable(reqWithOperator(OPERATOR), TENANT, STRATEGY, null),
        HttpStatus.SERVICE_UNAVAILABLE);
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
