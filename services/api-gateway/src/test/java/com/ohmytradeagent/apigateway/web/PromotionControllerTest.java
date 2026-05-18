package com.ohmytradeagent.apigateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.LivePromotionApprovalRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PromotionControllerTest {

  private WorkflowClient client;
  private WorkflowStub stub;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    client = mock(WorkflowClient.class);
    stub = mock(WorkflowStub.class);
    when(client.newUntypedWorkflowStub(any(String.class))).thenReturn(stub);

    TenantContext ctx = new TenantContext("dev", "copytrade-v1");
    PromotionController controller = new PromotionController(client, ctx);
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void approve_invokesActivityWithBothApprovers() throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/promotion/approve")
                .header("X-Operator-Id", "alice")
                .header("X-Approver-Id-2", "bob")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"broker_target\":\"tradier-live\",\"note\":\"phase-7 gate signoff drill\"}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status")
                .value("APPROVED"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.broker_target")
                .value("tradier-live"));

    ArgumentCaptor<LivePromotionApprovalRequest> cap =
        ArgumentCaptor.forClass(LivePromotionApprovalRequest.class);
    verify(stub).update(eq("record_live_promotion"), eq(Void.class), cap.capture());

    LivePromotionApprovalRequest captured = cap.getValue();
    assertThat(captured.getApproverId1()).isEqualTo("alice");
    assertThat(captured.getApproverId2()).isEqualTo("bob");
    assertThat(captured.getTenantId()).isEqualTo("dev");
    assertThat(captured.getStrategyId()).isEqualTo("copytrade-v1");
    assertThat(captured.getBrokerTarget()).isEqualTo("tradier-live");
    assertThat(captured.getNote()).isEqualTo("phase-7 gate signoff drill");
  }

  @Test
  void approve_missingApprover2_returns400() throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/promotion/approve")
                .header("X-Operator-Id", "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"broker_target\":\"tradier-live\"}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isBadRequest());
  }

  @Test
  void approve_sameApprover_returns400() throws Exception {
    // Same-approver pre-validation runs at the gateway, mapping directly through
    // GlobalExceptionHandler.IllegalArgumentException → HTTP 400. No Update fires.
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/promotion/approve")
                .header("X-Operator-Id", "alice")
                .header("X-Approver-Id-2", "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"broker_target\":\"tradier-live\"}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isBadRequest())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.detail")
                .value(org.hamcrest.Matchers.containsString("approvers_must_differ")));

    verify(stub, never()).update(any(String.class), any(Class.class), any());
  }
}
