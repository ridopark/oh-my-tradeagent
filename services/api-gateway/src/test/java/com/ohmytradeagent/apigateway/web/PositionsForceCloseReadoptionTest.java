package com.ohmytradeagent.apigateway.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.contract.ForceCloseResult;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowStub;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * #718 — recon adoption mints a NEW workflow id for the same position, so a caller's cached id
 * addresses a closed workflow while the position is still open. Reporting {@code
 * workflow_not_found} then tells the caller the position is gone when it is not, and nothing is
 * audited because {@code ForceCloseRequested} is emitted inside the workflow's update handler.
 */
class PositionsForceCloseReadoptionTest {

  private static final String OCC = "AMD   260819C00530000";
  private static final String STALE =
      WorkflowIds.position("dev", "copytrade-v1", OCC, "chat-77-1538925306302439515:0");
  private static final String LIVE =
      WorkflowIds.position("dev", "copytrade-v1", OCC, "chat-77-1538930696926531606:0");

  private WorkflowClient client;
  private WorkflowStub deadStub;
  private WorkflowStub liveStub;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    client = mock(WorkflowClient.class);
    deadStub = mock(WorkflowStub.class);
    liveStub = mock(WorkflowStub.class);
    when(client.newUntypedWorkflowStub(eq(STALE))).thenReturn(deadStub);
    when(client.newUntypedWorkflowStub(eq(LIVE))).thenReturn(liveStub);
    when(deadStub.update(eq("force_close"), eq(ForceCloseResult.class), any()))
        .thenThrow(
            new WorkflowNotFoundException(
                WorkflowExecution.newBuilder().setWorkflowId(STALE).build(),
                "PositionWorkflow",
                null));

    TenantContext ctx = new TenantContext("dev", "copytrade-v1");
    mvc =
        MockMvcBuilders.standaloneSetup(new PositionsController(client, ctx, "orchestrator-core"))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  private static WorkflowExecutionMetadata meta(String workflowId) {
    WorkflowExecutionMetadata meta = mock(WorkflowExecutionMetadata.class);
    when(meta.getExecution())
        .thenReturn(WorkflowExecution.newBuilder().setWorkflowId(workflowId).build());
    return meta;
  }

  private void running(String... workflowIds) {
    when(client.listExecutions(any()))
        .thenReturn(Stream.of(workflowIds).map(PositionsForceCloseReadoptionTest::meta));
  }

  private static ForceCloseResult accepted() {
    ForceCloseResult r = new ForceCloseResult();
    r.setSchemaVersion(1L);
    r.setStatus(ForceCloseResult.Status.ACCEPTED);
    r.setExitSignalId("force:dev:1");
    return r;
  }

  private org.springframework.test.web.servlet.ResultActions forceClose() throws Exception {
    return mvc.perform(
        post("/positions/force-close")
            .header("X-Operator-Id", "ops@example.com")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"workflowId\":\"" + STALE + "\",\"reason\":\"manual exit\"}"));
  }

  @Test
  void staleIdAfterAdoption_retriesAgainstTheLiveOwner() throws Exception {
    when(liveStub.update(eq("force_close"), eq(ForceCloseResult.class), any()))
        .thenReturn(accepted());
    running(LIVE);

    forceClose().andExpect(status().isAccepted());

    verify(liveStub).update(eq("force_close"), eq(ForceCloseResult.class), any());
  }

  /**
   * No running owner for the contract: the not-found is now a verified claim, not an assumption.
   */
  @Test
  void noLiveOwner_stillReportsWorkflowNotFound() throws Exception {
    running();

    forceClose()
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("workflow_not_found"));
  }

  /** Two owners of the same contract: a retry would be a coin flip over which lot to sell. */
  @Test
  void ambiguousOwners_refusesRatherThanGuessing() throws Exception {
    String other = WorkflowIds.position("dev", "copytrade-v1", OCC, "chat-77-999:0");
    WorkflowStub otherStub = mock(WorkflowStub.class);
    when(client.newUntypedWorkflowStub(eq(other))).thenReturn(otherStub);
    running(LIVE, other);

    forceClose().andExpect(status().isNotFound());

    verify(liveStub, never()).update(any(), any(), any());
    verify(otherStub, never()).update(any(), any(), any());
  }

  /** A different contract is not this position. */
  @Test
  void differentContractOpen_isNotRetriedAgainst() throws Exception {
    String otherWf =
        WorkflowIds.position("dev", "copytrade-v1", "NVDA  260821C00180000", "chat-77-555:0");
    WorkflowStub otherStub = mock(WorkflowStub.class);
    when(client.newUntypedWorkflowStub(eq(otherWf))).thenReturn(otherStub);
    running(otherWf);

    forceClose().andExpect(status().isNotFound());

    verify(otherStub, never()).update(any(), any(), any());
  }
}
