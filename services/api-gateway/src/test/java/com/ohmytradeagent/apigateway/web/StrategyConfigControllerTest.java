package com.ohmytradeagent.apigateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * UI-P3-b controller slice test against a mocked {@link WorkflowClient} + typed blocking stub.
 * Pins: tenant-mismatch 403 (no workflow started); absent X-Tenant-Id 400; the full outcome→HTTP
 * mapping (UPDATED 200 + new_version, STALE 409, DANGEROUS 403, INVALID 400, NOT_FOUND 404); and
 * the fail-safe — a WorkflowException (corrupt-row propagation / timeout) and a null/unknown
 * outcome BOTH map to 503, NEVER a misleading success.
 */
class StrategyConfigControllerTest {

  private static final String TENANT = "acme";
  private static final String STRATEGY = "copytrade-v1";

  private WorkflowClient workflowClient;
  private StrategyConfigUpdateWorkflow stub;
  private StrategyConfigController controller;

  @BeforeEach
  void setUp() {
    workflowClient = mock(WorkflowClient.class);
    stub = mock(StrategyConfigUpdateWorkflow.class);
    when(workflowClient.newWorkflowStub(any(Class.class), any(WorkflowOptions.class)))
        .thenReturn(stub);
    controller = new StrategyConfigController(workflowClient, new TenantContext("dev", STRATEGY));
  }

  private static HttpServletRequest reqWithTenant(String tenant) {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/strategy-config");
    if (tenant != null) {
      req.addHeader("X-Tenant-Id", tenant);
    }
    return req;
  }

  private static StrategyConfigWriteRequest body(String tenant) {
    return new StrategyConfigWriteRequest(tenant, STRATEGY, new StrategyConfig(), 3L, "corr-1");
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
  void updated_returns200_withNewVersion_andStartsWorkflowWithBuiltRequest() {
    when(stub.update(any(StrategyConfigUpdateRequest.class)))
        .thenReturn(resultOf(StrategyConfigUpdateResult.Outcome.UPDATED, 4L));

    var resp = controller.write(reqWithTenant(TENANT), body(TENANT));

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(resp.getBody()).containsEntry("status", "UPDATED").containsEntry("new_version", 4L);

    org.mockito.ArgumentCaptor<StrategyConfigUpdateRequest> captor =
        org.mockito.ArgumentCaptor.forClass(StrategyConfigUpdateRequest.class);
    verify(stub).update(captor.capture());
    StrategyConfigUpdateRequest req = captor.getValue();
    assertThat(req.getSchemaVersion()).isEqualTo(1L);
    assertThat(req.getTenantId()).isEqualTo(TENANT);
    assertThat(req.getStrategyId()).isEqualTo(STRATEGY);
    assertThat(req.getExpectedVersion()).isEqualTo(3L);
    assertThat(req.getActor()).isEqualTo("api-gateway:/strategy-config");
    assertThat(req.getCorrelationId()).isEqualTo("corr-1");
  }

  @Test
  void staleVersion_returns409() {
    assertStatus(StrategyConfigUpdateResult.Outcome.REJECTED_STALE_VERSION, HttpStatus.CONFLICT);
  }

  @Test
  void dangerous_returns403() {
    assertStatus(StrategyConfigUpdateResult.Outcome.REJECTED_DANGEROUS, HttpStatus.FORBIDDEN);
  }

  @Test
  void invalid_returns400() {
    assertStatus(StrategyConfigUpdateResult.Outcome.REJECTED_INVALID, HttpStatus.BAD_REQUEST);
  }

  @Test
  void notFound_returns404() {
    assertStatus(StrategyConfigUpdateResult.Outcome.NOT_FOUND, HttpStatus.NOT_FOUND);
  }

  @Test
  void persistErrorOutcome_returns503_neverSuccess() {
    // The activity does not emit REJECTED_PERSIST_ERROR, but if it ever did the caller must NOT
    // treat it as success — it falls into the 503 unknown-disposition bucket.
    when(stub.update(any(StrategyConfigUpdateRequest.class)))
        .thenReturn(resultOf(StrategyConfigUpdateResult.Outcome.REJECTED_PERSIST_ERROR, null));
    assertResponseStatus(
        () -> controller.write(reqWithTenant(TENANT), body(TENANT)),
        HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  void nullOutcome_returns503() {
    when(stub.update(any(StrategyConfigUpdateRequest.class))).thenReturn(resultOf(null, null));
    assertResponseStatus(
        () -> controller.write(reqWithTenant(TENANT), body(TENANT)),
        HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  void workflowFailure_returns503_neverSuccess() {
    when(stub.update(any(StrategyConfigUpdateRequest.class)))
        .thenThrow(new TestWorkflowException());
    assertResponseStatus(
        () -> controller.write(reqWithTenant(TENANT), body(TENANT)),
        HttpStatus.SERVICE_UNAVAILABLE);
  }

  /**
   * A concrete {@link WorkflowException} for the failure-path test ({@code WorkflowException} is
   * abstract; the real {@code WorkflowFailedException} constructor needs protobuf scaffolding we
   * don't want to fabricate here). Stands in for the corrupt-row propagation / timeout the
   * controller maps to 503.
   */
  private static final class TestWorkflowException extends WorkflowException {
    TestWorkflowException() {
      super("simulated workflow failure", WorkflowExecution.getDefaultInstance(), null, null);
    }
  }

  @Test
  void tenantMismatch_is403_noWorkflowStarted() {
    assertResponseStatus(
        () -> controller.write(reqWithTenant(TENANT), body("other-tenant")), HttpStatus.FORBIDDEN);
    verify(workflowClient, never()).newWorkflowStub(any(Class.class), any(WorkflowOptions.class));
  }

  @Test
  void absentTenantHeader_is400_noWorkflowStarted() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> controller.write(reqWithTenant(null), body(TENANT)))
        .isInstanceOf(TenantContext.MissingHeaderException.class);
    verify(workflowClient, never()).newWorkflowStub(any(Class.class), any(WorkflowOptions.class));
  }

  private void assertStatus(StrategyConfigUpdateResult.Outcome outcome, HttpStatus expected) {
    when(stub.update(any(StrategyConfigUpdateRequest.class))).thenReturn(resultOf(outcome, null));
    var resp = controller.write(reqWithTenant(TENANT), body(TENANT));
    assertThat(resp.getStatusCode()).isEqualTo(expected);
    assertThat(resp.getBody()).containsEntry("status", outcome.value());
    assertThat(resp.getBody()).doesNotContainKey("new_version");
  }

  private static void assertResponseStatus(Executable e, HttpStatus expected) {
    try {
      e.execute();
      org.assertj.core.api.Assertions.fail("expected ResponseStatusException " + expected);
    } catch (ResponseStatusException ex) {
      assertThat(ex.getStatusCode()).isEqualTo(expected);
    } catch (Throwable t) {
      org.assertj.core.api.Assertions.fail("unexpected throwable", t);
    }
  }
}
