package com.ohmytradeagent.tdbff.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.contract.PartialCloseRequest;
import com.ohmytradeagent.contract.PartialCloseResult;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer guards for the tenant self-service per-position {@code POST
 * /api/positions/partial-close} ("Trim"). The write flag is ON here so the guard paths (fail-closed
 * tenant, cross-tenant isolation, fraction validation, ACCEPTED→202 mapping) are exercised; the
 * dark-launch (flag off → 404) case is in {@link PositionsPartialCloseDarkLaunchTest}.
 */
@WebMvcTest(PositionsController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
@TestPropertySource(properties = "positions.partial-close.write-enabled=true")
class PositionsPartialCloseControllerWebMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private WorkflowClient client;

  // PositionsController also depends on PositionsReader for its GET; mock it so the context loads.
  @MockitoBean private com.ohmytradeagent.tdbff.positions.PositionsReader reader;

  private WorkflowStub stub;

  @BeforeEach
  void setUp() {
    stub = mock(WorkflowStub.class);
    when(client.newUntypedWorkflowStub(anyString())).thenReturn(stub);
  }

  private static String ownWorkflowId() {
    return WorkflowIds.position("acme", "copytrade-v1", "AAPL260727C00330000", "sig1");
  }

  private static PartialCloseResult accepted(String exitSignalId) {
    PartialCloseResult r = new PartialCloseResult();
    r.setSchemaVersion(1L);
    r.setStatus(PartialCloseResult.Status.ACCEPTED);
    r.setExitSignalId(exitSignalId);
    return r;
  }

  private static String body(String workflowId, String fractionJson) {
    return "{\"workflow_id\":\""
        + workflowId
        + "\",\"reason\":\"operator trim\",\"fraction\":"
        + fractionJson
        + "}";
  }

  @Test
  void partialClose_flagOn_accepted_returns202() throws Exception {
    when(stub.update(eq("partial_close"), eq(PartialCloseResult.class), any()))
        .thenReturn(accepted("trim:tenant:acme:42"));

    mvc.perform(
            post("/api/positions/partial-close")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(ownWorkflowId(), "0.5")))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("ACCEPTED"))
        .andExpect(jsonPath("$.exit_signal_id").value("trim:tenant:acme:42"));

    ArgumentCaptor<PartialCloseRequest> cap = ArgumentCaptor.forClass(PartialCloseRequest.class);
    org.mockito.Mockito.verify(stub)
        .update(eq("partial_close"), eq(PartialCloseResult.class), cap.capture());
    PartialCloseRequest sent = cap.getValue();
    assertThat(sent.getSchemaVersion()).isEqualTo(1L);
    // The tenant never supplies operator_id on the /live path: the BFF stamps "tenant:<tenant>".
    assertThat(sent.getOperatorId()).isEqualTo("tenant:acme");
    assertThat(sent.getReason()).isEqualTo("operator trim");
    assertThat(sent.getFraction()).isEqualByComparingTo(new BigDecimal("0.5"));
    org.mockito.Mockito.verify(client).newUntypedWorkflowStub(eq(ownWorkflowId()));
  }

  @Test
  void partialClose_noopAlreadyClosed_returns200() throws Exception {
    PartialCloseResult noop = new PartialCloseResult();
    noop.setSchemaVersion(1L);
    noop.setStatus(PartialCloseResult.Status.NOOP_ALREADY_CLOSED);
    noop.setExitSignalId("trim:tenant:acme:7");
    when(stub.update(eq("partial_close"), eq(PartialCloseResult.class), any())).thenReturn(noop);

    mvc.perform(
            post("/api/positions/partial-close")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(ownWorkflowId(), "0.25")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("NOOP_ALREADY_CLOSED"));
  }

  @Test
  void partialClose_alreadyTerminatedWorkflow_returns409() throws Exception {
    // The render-vs-click race: the PositionWorkflow completed between the /live render and the
    // click. Temporal's stub.update throws WorkflowNotFoundException → friendly 409, not a 500.
    WorkflowExecution exec = WorkflowExecution.newBuilder().setWorkflowId(ownWorkflowId()).build();
    when(stub.update(eq("partial_close"), eq(PartialCloseResult.class), any()))
        .thenThrow(new WorkflowNotFoundException(exec, "PositionWorkflow", null));

    mvc.perform(
            post("/api/positions/partial-close")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(ownWorkflowId(), "0.5")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("position_already_closed"));
  }

  @Test
  void partialClose_missingTenantHeader_failClosed() throws Exception {
    mvc.perform(
            post("/api/positions/partial-close")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(ownWorkflowId(), "0.5")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("missing_tenant"));

    verifyNoInteractions(client);
  }

  @Test
  void partialClose_crossTenantWorkflowId_rejected403() throws Exception {
    String otherTenantId =
        WorkflowIds.position("other", "copytrade-v1", "AAPL260727C00330000", "sig1");
    mvc.perform(
            post("/api/positions/partial-close")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(otherTenantId, "0.5")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("cross_tenant_workflow_id"));

    verifyNoInteractions(client);
  }

  @Test
  void partialClose_nonPositionWorkflowId_rejected403() throws Exception {
    // The tenant's OWN killswitch id shares the "t-acme/" prefix but has no "/pos/" segment.
    String ownKillswitchId = WorkflowIds.killswitch("acme", "copytrade-v1");
    mvc.perform(
            post("/api/positions/partial-close")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(ownKillswitchId, "0.5")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("not_a_position_workflow_id"));

    verifyNoInteractions(client);
  }

  @Test
  void partialClose_fullFraction_rejected400() throws Exception {
    // fraction == 1.0 is a FULL close — force-close's job. Rejected before any Temporal call so a
    // mis-parameterized trim can never flatten the whole position.
    mvc.perform(
            post("/api/positions/partial-close")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(ownWorkflowId(), "1.0")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("bad_request"));

    verifyNoInteractions(client);
  }

  @Test
  void partialClose_missingFraction_rejected400() throws Exception {
    mvc.perform(
            post("/api/positions/partial-close")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflow_id\":\"" + ownWorkflowId() + "\",\"reason\":\"trim\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("bad_request"));

    verifyNoInteractions(client);
  }

  @Test
  void partialClose_zeroFraction_rejected400() throws Exception {
    mvc.perform(
            post("/api/positions/partial-close")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(ownWorkflowId(), "0")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("bad_request"));

    verifyNoInteractions(client);
  }

  @Test
  void partialClose_blankReason_rejected400() throws Exception {
    mvc.perform(
            post("/api/positions/partial-close")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"workflow_id\":\""
                        + ownWorkflowId()
                        + "\",\"reason\":\"   \",\"fraction\":0.5}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("bad_request"));

    verifyNoInteractions(client);
  }

  @Test
  void partialClose_withOperatorHeader_threadsActorIntoOperatorId() throws Exception {
    when(stub.update(eq("partial_close"), eq(PartialCloseResult.class), any()))
        .thenReturn(accepted("trim:tenant:acme:1"));

    mvc.perform(
            post("/api/positions/partial-close")
                .header("X-Tenant-Id", "acme")
                .header("X-Operator-Id", "alice@example.com")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(ownWorkflowId(), "0.75")))
        .andExpect(status().isAccepted());

    ArgumentCaptor<PartialCloseRequest> cap = ArgumentCaptor.forClass(PartialCloseRequest.class);
    org.mockito.Mockito.verify(stub)
        .update(eq("partial_close"), eq(PartialCloseResult.class), cap.capture());
    assertThat(cap.getValue().getOperatorId()).isEqualTo("tenant:acme:alice@example.com");
  }
}
