package com.ohmytradeagent.apigateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.contract.AdoptionResult;
import com.ohmytradeagent.contract.AdoptionWorkflowInput;
import com.ohmytradeagent.contract.ForceCloseRequest;
import com.ohmytradeagent.contract.ForceCloseResult;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PositionsControllerTest {

  private WorkflowClient client;
  private WorkflowStub stub;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    client = mock(WorkflowClient.class);
    stub = mock(WorkflowStub.class);
    when(client.newUntypedWorkflowStub(any(String.class))).thenReturn(stub);

    TenantContext ctx = new TenantContext("dev", "copytrade-v1");
    PositionsController controller = new PositionsController(client, ctx, "orchestrator-core");
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void list_filtersByTenantStrategyAndStatus() throws Exception {
    WorkflowExecutionMetadata meta = mock(WorkflowExecutionMetadata.class);
    when(meta.getExecution())
        .thenReturn(
            WorkflowExecution.newBuilder()
                .setWorkflowId("t-dev/s-copytrade-v1/pos/AAPL260620C00150000/sig-1")
                .setRunId("run-1")
                .build());
    when(meta.getStartTime()).thenReturn(Instant.parse("2026-05-14T13:00:00Z"));
    when(client.listExecutions(any())).thenReturn(Stream.of(meta));

    mvc.perform(get("/positions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(1))
        .andExpect(
            jsonPath("$.items[0].workflow_id")
                .value("t-dev/s-copytrade-v1/pos/AAPL260620C00150000/sig-1"));

    ArgumentCaptor<String> q = ArgumentCaptor.forClass(String.class);
    verify(client).listExecutions(q.capture());
    assertThat(q.getValue())
        .contains("TenantStrategy = 't-dev/s-copytrade-v1'")
        .contains("WorkflowType = 'PositionWorkflow'")
        .contains("ExecutionStatus = 'WORKFLOW_EXECUTION_STATUS_RUNNING'");
  }

  @Test
  void forceClose_acceptedReturns202WithExitSignalId() throws Exception {
    ForceCloseResult result = new ForceCloseResult();
    result.setSchemaVersion(1L);
    result.setExitSignalId("force:ridopark:1715703000123");
    result.setStatus(ForceCloseResult.Status.ACCEPTED);
    when(stub.update(eq("force_close"), eq(ForceCloseResult.class), any())).thenReturn(result);

    mvc.perform(
            post("/positions/force-close")
                .header("X-Operator-Id", "ridopark")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"workflowId\":\"t-dev/s-copytrade-v1/pos/AAPL260620C00150000/sig-1\",\"reason\":\"risk-team-call\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.exit_signal_id").value("force:ridopark:1715703000123"))
        .andExpect(jsonPath("$.status").value("ACCEPTED"));

    ArgumentCaptor<ForceCloseRequest> cap = ArgumentCaptor.forClass(ForceCloseRequest.class);
    verify(stub).update(eq("force_close"), eq(ForceCloseResult.class), cap.capture());
    assertThat(cap.getValue().getOperatorId()).isEqualTo("ridopark");
    assertThat(cap.getValue().getReason()).isEqualTo("risk-team-call");
  }

  @Test
  void forceClose_workflowIdOutsideTenantStrategy_returns400() throws Exception {
    mvc.perform(
            post("/positions/force-close")
                .header("X-Operator-Id", "ridopark")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"workflowId\":\"t-other/s-copytrade-v1/pos/AAPL260620C00150000/sig-1\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void forceClose_noopReturns200() throws Exception {
    ForceCloseResult result = new ForceCloseResult();
    result.setSchemaVersion(1L);
    result.setExitSignalId("force:ridopark:1715703000123");
    result.setStatus(ForceCloseResult.Status.NOOP_ALREADY_CLOSED);
    when(stub.update(eq("force_close"), eq(ForceCloseResult.class), any())).thenReturn(result);

    mvc.perform(
            post("/positions/force-close")
                .header("X-Operator-Id", "ridopark")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflowId\":\"t-dev/s-copytrade-v1/pos/AAPL260620C00150000/sig-1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("NOOP_ALREADY_CLOSED"));
  }

  @Test
  void adopt_adoptedReturns200_withWorkflowIdAndProvenance() throws Exception {
    AdoptionResult result = new AdoptionResult();
    result.setSchemaVersion(1L);
    result.setOutcome(AdoptionResult.Outcome.ADOPTED);
    result.setWorkflowId("t-dev/s-copytrade-v1/pos/UNH   260618C00400000/sig-abc");
    result.setEntrySignalId("sig-abc");
    result.setQty(5L);
    when(client.newUntypedWorkflowStub(eq("AdoptionWorkflow"), any(WorkflowOptions.class)))
        .thenReturn(stub);
    when(stub.getResult(AdoptionResult.class)).thenReturn(result);

    mvc.perform(
            post("/positions/adopt")
                .header("X-Operator-Id", "ridopark")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"occ\":\"UNH260618C00400000\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("ADOPTED"))
        .andExpect(jsonPath("$.qty").value(5))
        .andExpect(jsonPath("$.entry_signal_id").value("sig-abc"));

    // Adoption workflow started on the orchestrator queue with operator + occ from the request.
    ArgumentCaptor<WorkflowOptions> optsCap = ArgumentCaptor.forClass(WorkflowOptions.class);
    verify(client).newUntypedWorkflowStub(eq("AdoptionWorkflow"), optsCap.capture());
    assertThat(optsCap.getValue().getTaskQueue()).isEqualTo("orchestrator-core");
    assertThat(optsCap.getValue().getWorkflowId())
        .isEqualTo("t-dev/s-copytrade-v1/adopt/UNH260618C00400000");

    ArgumentCaptor<Object> startCap = ArgumentCaptor.forClass(Object.class);
    verify(stub).start(startCap.capture());
    AdoptionWorkflowInput in = (AdoptionWorkflowInput) startCap.getValue();
    assertThat(in.getTenantId()).isEqualTo("dev");
    assertThat(in.getStrategyId()).isEqualTo("copytrade-v1");
    assertThat(in.getOcc()).isEqualTo("UNH260618C00400000");
    assertThat(in.getOperatorId()).isEqualTo("ridopark");
  }

  @Test
  void adopt_refusedNotHeldReturns409() throws Exception {
    AdoptionResult result = new AdoptionResult();
    result.setSchemaVersion(1L);
    result.setOutcome(AdoptionResult.Outcome.REFUSED_NOT_HELD);
    when(client.newUntypedWorkflowStub(eq("AdoptionWorkflow"), any(WorkflowOptions.class)))
        .thenReturn(stub);
    when(stub.getResult(AdoptionResult.class)).thenReturn(result);

    mvc.perform(
            post("/positions/adopt")
                .header("X-Operator-Id", "ridopark")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"occ\":\"UNH260618C00400000\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.outcome").value("REFUSED_NOT_HELD"));
  }

  @Test
  void adopt_alreadyOwnedReturns200() throws Exception {
    AdoptionResult result = new AdoptionResult();
    result.setSchemaVersion(1L);
    result.setOutcome(AdoptionResult.Outcome.ALREADY_OWNED);
    when(client.newUntypedWorkflowStub(eq("AdoptionWorkflow"), any(WorkflowOptions.class)))
        .thenReturn(stub);
    when(stub.getResult(AdoptionResult.class)).thenReturn(result);

    mvc.perform(
            post("/positions/adopt")
                .header("X-Operator-Id", "ridopark")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"occ\":\"UNH260618C00400000\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("ALREADY_OWNED"));
  }

  @Test
  void adopt_missingOperatorHeaderReturns400() throws Exception {
    mvc.perform(
            post("/positions/adopt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"occ\":\"UNH260618C00400000\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void adopt_missingOccReturns400() throws Exception {
    mvc.perform(
            post("/positions/adopt")
                .header("X-Operator-Id", "ridopark")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
  }
}
