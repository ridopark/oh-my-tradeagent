package com.ohmytradeagent.apigateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.TenantConfigUpdateRequest;
import com.ohmytradeagent.contract.TenantConfigUpdateResult;
import com.ohmytradeagent.orchestrator.workflows.TenantConfigUpdateWorkflow;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowOptions;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * account-loss-cap-db (Phase 3) controller slice test against a mocked {@link WorkflowClient} +
 * typed blocking stub. Pins: tenant-mismatch 403 (no workflow started); absent X-Tenant-Id 400; the
 * full outcome→HTTP mapping (UPDATED 200 + new_version, TIGHTEN_ONLY 403, BELOW_FLOOR 422, STALE
 * 409, INVALID 400, NOT_FOUND 404); and the fail-safe — a WorkflowException (timeout / propagation)
 * and a null outcome BOTH map to 503, NEVER a misleading success.
 */
class TenantConfigControllerTest {

  private static final String TENANT = "acme";

  private WorkflowClient workflowClient;
  private TenantConfigUpdateWorkflow stub;
  private TenantConfigController controller;

  @BeforeEach
  void setUp() {
    workflowClient = mock(WorkflowClient.class);
    stub = mock(TenantConfigUpdateWorkflow.class);
    when(workflowClient.newWorkflowStub(any(Class.class), any(WorkflowOptions.class)))
        .thenReturn(stub);
    controller =
        new TenantConfigController(workflowClient, new TenantContext("dev", "copytrade-v1"));
  }

  private static HttpServletRequest reqWithTenant(String tenant) {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/tenant-config");
    if (tenant != null) {
      req.addHeader("X-Tenant-Id", tenant);
    }
    return req;
  }

  private static TenantConfigWriteRequest body(String tenant) {
    return new TenantConfigWriteRequest(
        tenant, new BigDecimal("2000"), new BigDecimal("0.30"), 3L, "corr-1");
  }

  private static TenantConfigUpdateResult resultOf(
      TenantConfigUpdateResult.Outcome outcome, Long newVersion) {
    TenantConfigUpdateResult r = new TenantConfigUpdateResult();
    r.setSchemaVersion(1L);
    r.setOutcome(outcome);
    r.setNewVersion(newVersion);
    return r;
  }

  @Test
  void updated_returns200_withNewVersion_andStartsWorkflowWithBuiltRequest() {
    when(stub.update(any(TenantConfigUpdateRequest.class)))
        .thenReturn(resultOf(TenantConfigUpdateResult.Outcome.UPDATED, 4L));

    var resp = controller.write(reqWithTenant(TENANT), body(TENANT));

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(resp.getBody()).containsEntry("status", "UPDATED").containsEntry("new_version", 4L);

    org.mockito.ArgumentCaptor<TenantConfigUpdateRequest> captor =
        org.mockito.ArgumentCaptor.forClass(TenantConfigUpdateRequest.class);
    verify(stub).update(captor.capture());
    TenantConfigUpdateRequest req = captor.getValue();
    assertThat(req.getSchemaVersion()).isEqualTo(1L);
    assertThat(req.getTenantId()).isEqualTo(TENANT);
    assertThat(req.getAccountDailyLossThreshold()).isEqualByComparingTo("2000");
    assertThat(req.getAccountDailyLossPct()).isEqualByComparingTo("0.30");
    assertThat(req.getExpectedVersion()).isEqualTo(3L);
    assertThat(req.getActor()).isEqualTo("api-gateway:/tenant-config");
    assertThat(req.getCorrelationId()).isEqualTo("corr-1");
  }

  @Test
  void tightenOnly_returns403() {
    assertStatus(TenantConfigUpdateResult.Outcome.REJECTED_TIGHTEN_ONLY, HttpStatus.FORBIDDEN);
  }

  @Test
  void belowFloor_returns422() {
    assertStatus(
        TenantConfigUpdateResult.Outcome.REJECTED_BELOW_FLOOR, HttpStatus.UNPROCESSABLE_ENTITY);
  }

  @Test
  void staleVersion_returns409() {
    assertStatus(TenantConfigUpdateResult.Outcome.REJECTED_STALE_VERSION, HttpStatus.CONFLICT);
  }

  @Test
  void invalid_returns400() {
    assertStatus(TenantConfigUpdateResult.Outcome.REJECTED_INVALID, HttpStatus.BAD_REQUEST);
  }

  @Test
  void notFound_returns404() {
    assertStatus(TenantConfigUpdateResult.Outcome.NOT_FOUND, HttpStatus.NOT_FOUND);
  }

  @Test
  void nullOutcome_returns503() {
    when(stub.update(any(TenantConfigUpdateRequest.class))).thenReturn(resultOf(null, null));
    assertResponseStatus(
        () -> controller.write(reqWithTenant(TENANT), body(TENANT)),
        HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  void workflowFailure_returns503_neverSuccess() {
    when(stub.update(any(TenantConfigUpdateRequest.class))).thenThrow(new TestWorkflowException());
    assertResponseStatus(
        () -> controller.write(reqWithTenant(TENANT), body(TENANT)),
        HttpStatus.SERVICE_UNAVAILABLE);
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

  private void assertStatus(TenantConfigUpdateResult.Outcome outcome, HttpStatus expected) {
    when(stub.update(any(TenantConfigUpdateRequest.class))).thenReturn(resultOf(outcome, null));
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

  /**
   * A concrete {@link WorkflowException} for the failure-path test ({@code WorkflowException} is
   * abstract). Stands in for the timeout / propagation the controller maps to 503.
   */
  private static final class TestWorkflowException extends WorkflowException {
    TestWorkflowException() {
      super("simulated workflow failure", WorkflowExecution.getDefaultInstance(), null, null);
    }
  }
}
