package com.ohmytradeagent.apigateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ohmytradeagent.contract.KillSwitchState;
import com.ohmytradeagent.contract.ResetKillSwitchRequest;
import com.ohmytradeagent.contract.TripKillSwitchRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowStub;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class KillSwitchControllerTest {

  private WorkflowClient client;
  private WorkflowStub stub;
  private MockMvc mvc;
  private ObjectMapper json;

  @BeforeEach
  void setUp() {
    client = mock(WorkflowClient.class);
    stub = mock(WorkflowStub.class);
    when(client.newUntypedWorkflowStub(any(String.class))).thenReturn(stub);

    TenantContext ctx = new TenantContext("dev", "copytrade-v1");
    KillSwitchController controller = new KillSwitchController(client, ctx);
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    json = new ObjectMapper().registerModule(new JavaTimeModule());
  }

  @Test
  void state_returnsQueryResult() throws Exception {
    KillSwitchState state = new KillSwitchState();
    state.setSchemaVersion(1L);
    state.setTripped(false);
    state.setReason("");
    state.setActor("");
    when(stub.query(eq("killswitch_state"), eq(KillSwitchState.class))).thenReturn(state);

    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/killswitch"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.tripped")
                .value(false));
  }

  @Test
  void state_workflowNotFound_returns404() throws Exception {
    when(stub.query(any(), any()))
        .thenThrow(
            new WorkflowNotFoundException(
                io.temporal.api.common.v1.WorkflowExecution.newBuilder().build(),
                "KillSwitchWorkflow",
                new RuntimeException("not found")));

    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/killswitch"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isNotFound());
  }

  @Test
  void trip_invokesUpdate() throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/killswitch/trip")
                .header("X-Operator-Id", "ridopark")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"manual:rsi-spike\"}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

    ArgumentCaptor<TripKillSwitchRequest> cap =
        ArgumentCaptor.forClass(TripKillSwitchRequest.class);
    verify(stub).update(eq("trip_killswitch"), eq(Void.class), cap.capture());
    assertThat(cap.getValue().getReason()).isEqualTo("manual:rsi-spike");
    assertThat(cap.getValue().getActor()).isEqualTo("operator:ridopark");
  }

  @Test
  void trip_notGatedByOperatorAllowlist_emptyAllowlistStillTrips() throws Exception {
    // C3 guard: the ctx here has an EMPTY operator allowlist (deny-all for the operator-ADMIN
    // routes). The kill-switch trip must NOT be gated by that allowlist — a safety control can
    // never be blocked by a misconfigured/empty admin allowlist. A non-allowlisted operator still
    // trips (200).
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/killswitch/trip")
                .header("X-Operator-Id", "not-in-any-allowlist@example.com")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"manual:safety\"}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

    verify(stub).update(eq("trip_killswitch"), eq(Void.class), any(TripKillSwitchRequest.class));
  }

  @Test
  void trip_missingOperatorHeader_returns400() throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/killswitch/trip")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"x\"}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isBadRequest());
  }

  @Test
  void reset_invokesUpdateWithBothApprovers() throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/killswitch/reset")
                .header("X-Operator-Id", "alice")
                .header("X-Approver-Id-2", "bob")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"PnL recovered after correction\"}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

    ArgumentCaptor<ResetKillSwitchRequest> cap =
        ArgumentCaptor.forClass(ResetKillSwitchRequest.class);
    verify(stub).update(eq("reset_killswitch"), eq(Void.class), cap.capture());
    assertThat(cap.getValue().getApproverId1()).isEqualTo("alice");
    assertThat(cap.getValue().getApproverId2()).isEqualTo("bob");
  }

  @Test
  void reset_missingApprover2_returns400() throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/killswitch/reset")
                .header("X-Operator-Id", "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isBadRequest());
  }

  // Silence unused-import warnings for OffsetDateTime; available for tests that need it.
  @SuppressWarnings("unused")
  private static OffsetDateTime fixedNow() {
    return OffsetDateTime.parse("2026-05-14T15:00:00Z");
  }
}
