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
import com.ohmytradeagent.contract.StrategyConfigCreateRequest;
import com.ohmytradeagent.contract.StrategyConfigCreateResult;
import com.ohmytradeagent.orchestrator.workflows.StrategyConfigCreateWorkflow;
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
 * Phase I-1b controller-slice test against a mocked {@link WorkflowClient} + typed blocking stub.
 * Pins: missing X-Operator-Id → 400 (no workflow started); CREATED → 200 (+created_version);
 * ALREADY_EXISTS → 409; REJECTED_INVALID → 400; a WorkflowException (timeout / wedge) → 503; a null
 * outcome → 503. (The bearer-missing 401 is enforced by ServiceTokenFilter; the flag-off 404 is
 * bean absence — the controller bean only exists under operator.tenant-create.enabled=true.)
 */
class CreateTenantControllerTest {

  private static final String TENANT = "acme";
  private static final String STRATEGY = "copytrade-v1";
  private static final String OPERATOR = "ridopark@gmail.com";

  private WorkflowClient workflowClient;
  private StrategyConfigCreateWorkflow stub;
  private CreateTenantController controller;

  @BeforeEach
  void setUp() {
    workflowClient = mock(WorkflowClient.class);
    stub = mock(StrategyConfigCreateWorkflow.class);
    when(workflowClient.newWorkflowStub(
            eq(StrategyConfigCreateWorkflow.class), any(WorkflowOptions.class)))
        .thenReturn(stub);
    controller = new CreateTenantController(workflowClient, new TenantContext("dev", STRATEGY));
  }

  private static HttpServletRequest reqWithOperator(String operator) {
    MockHttpServletRequest req =
        new MockHttpServletRequest("POST", "/admin/tenants/" + TENANT + "/strategies/" + STRATEGY);
    if (operator != null) {
      req.addHeader("X-Operator-Id", operator);
    }
    return req;
  }

  private static TenantCreateRequest body() {
    return new TenantCreateRequest(new StrategyConfig(), "corr-1");
  }

  private static StrategyConfigCreateResult result(
      StrategyConfigCreateResult.Outcome outcome, Long version) {
    StrategyConfigCreateResult r = new StrategyConfigCreateResult();
    r.setSchemaVersion(1L);
    r.setOutcome(outcome);
    r.setCreatedVersion(version);
    return r;
  }

  @Test
  void missingOperatorHeader_is400_noWorkflowStarted() {
    assertThatThrownBy(() -> controller.create(reqWithOperator(null), TENANT, STRATEGY, body()))
        .isInstanceOf(TenantContext.MissingHeaderException.class);
    verify(workflowClient, never()).newWorkflowStub(any(Class.class), any(WorkflowOptions.class));
  }

  @Test
  void created_returns200_withCreatedVersion() {
    when(stub.create(any(StrategyConfigCreateRequest.class)))
        .thenReturn(result(StrategyConfigCreateResult.Outcome.CREATED, 1L));

    var resp = controller.create(reqWithOperator(OPERATOR), TENANT, STRATEGY, body());

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(resp.getBody())
        .containsEntry("status", "CREATED")
        .containsEntry("created_version", 1L);
  }

  @Test
  void alreadyExists_returns409() {
    when(stub.create(any(StrategyConfigCreateRequest.class)))
        .thenReturn(result(StrategyConfigCreateResult.Outcome.ALREADY_EXISTS, null));

    var resp = controller.create(reqWithOperator(OPERATOR), TENANT, STRATEGY, body());

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(resp.getBody()).containsEntry("status", "ALREADY_EXISTS");
  }

  @Test
  void rejectedInvalid_returns400() {
    when(stub.create(any(StrategyConfigCreateRequest.class)))
        .thenReturn(result(StrategyConfigCreateResult.Outcome.REJECTED_INVALID, null));

    var resp = controller.create(reqWithOperator(OPERATOR), TENANT, STRATEGY, body());

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(resp.getBody()).containsEntry("status", "REJECTED_INVALID");
  }

  @Test
  void workflowFailure_returns503() {
    when(stub.create(any(StrategyConfigCreateRequest.class)))
        .thenThrow(new TestWorkflowException());

    assertResponseStatus(
        () -> controller.create(reqWithOperator(OPERATOR), TENANT, STRATEGY, body()),
        HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  void nullOutcome_returns503() {
    when(stub.create(any(StrategyConfigCreateRequest.class))).thenReturn(result(null, null));

    assertResponseStatus(
        () -> controller.create(reqWithOperator(OPERATOR), TENANT, STRATEGY, body()),
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
