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

import com.ohmytradeagent.contract.ForceCloseRequest;
import com.ohmytradeagent.contract.ForceCloseResult;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowStub;
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
 * /api/positions/force-close}. The write flag is ON here so the guard paths (fail-closed tenant,
 * cross-tenant isolation, blank-reason validation, ACCEPTED→202 mapping) are exercised; the
 * dark-launch (flag off → 404) case is in {@link PositionsForceCloseDarkLaunchTest}.
 */
@WebMvcTest(PositionsController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
@TestPropertySource(properties = "positions.force-close.write-enabled=true")
class PositionsForceCloseControllerWebMvcTest {

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

  private static ForceCloseResult accepted(String exitSignalId) {
    ForceCloseResult r = new ForceCloseResult();
    r.setSchemaVersion(1L);
    r.setStatus(ForceCloseResult.Status.ACCEPTED);
    r.setExitSignalId(exitSignalId);
    return r;
  }

  @Test
  void forceClose_flagOn_accepted_returns202() throws Exception {
    String ownWorkflowId =
        WorkflowIds.position("acme", "copytrade-v1", "AAPL260727C00330000", "sig1");
    when(stub.update(eq("force_close"), eq(ForceCloseResult.class), any()))
        .thenReturn(accepted("force:tenant:acme:42"));

    mvc.perform(
            post("/api/positions/force-close")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"workflow_id\":\""
                        + ownWorkflowId
                        + "\",\"reason\":\"operator manual exit\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("ACCEPTED"))
        .andExpect(jsonPath("$.exit_signal_id").value("force:tenant:acme:42"));

    // The tenant never supplies operator_id on the /live path: the BFF stamps "tenant:<tenant>".
    ArgumentCaptor<ForceCloseRequest> cap = ArgumentCaptor.forClass(ForceCloseRequest.class);
    org.mockito.Mockito.verify(stub)
        .update(eq("force_close"), eq(ForceCloseResult.class), cap.capture());
    ForceCloseRequest sent = cap.getValue();
    assertThat(sent.getSchemaVersion()).isEqualTo(1L);
    assertThat(sent.getOperatorId()).isEqualTo("tenant:acme");
    assertThat(sent.getReason()).isEqualTo("operator manual exit");
    // The addressed workflow id is exactly the caller-supplied own-tenant id.
    org.mockito.Mockito.verify(client).newUntypedWorkflowStub(eq(ownWorkflowId));
  }

  @Test
  void forceClose_alreadyTerminatedWorkflow_returns409NotFound() throws Exception {
    // The headline race: the PositionWorkflow completed/terminated between the /live render and the
    // click (self-heal, EOD flatten, or an already-cleared phantom). Temporal's stub.update then
    // throws WorkflowNotFoundException. It must surface as a friendly 409
    // "position_already_closed",
    // NOT a 500 through the catch-all.
    String ownWorkflowId =
        WorkflowIds.position("acme", "copytrade-v1", "AAPL260727C00330000", "sig1");
    WorkflowExecution exec = WorkflowExecution.newBuilder().setWorkflowId(ownWorkflowId).build();
    when(stub.update(eq("force_close"), eq(ForceCloseResult.class), any()))
        .thenThrow(new WorkflowNotFoundException(exec, "PositionWorkflow", null));

    mvc.perform(
            post("/api/positions/force-close")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflow_id\":\"" + ownWorkflowId + "\",\"reason\":\"manual exit\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("position_already_closed"));
  }

  @Test
  void forceClose_missingTenantHeader_failClosed() throws Exception {
    String ownWorkflowId =
        WorkflowIds.position("acme", "copytrade-v1", "AAPL260727C00330000", "sig1");
    mvc.perform(
            post("/api/positions/force-close")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflow_id\":\"" + ownWorkflowId + "\",\"reason\":\"x\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("missing_tenant"));

    // No workflow addressed at all when the tenant is unresolved.
    verifyNoInteractions(client);
  }

  @Test
  void forceClose_crossTenantWorkflowId_rejected403() throws Exception {
    // A workflow id for a DIFFERENT tenant must be rejected BEFORE any Temporal call.
    String otherTenantId =
        WorkflowIds.position("other", "copytrade-v1", "AAPL260727C00330000", "sig1");
    mvc.perform(
            post("/api/positions/force-close")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflow_id\":\"" + otherTenantId + "\",\"reason\":\"x\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("cross_tenant_workflow_id"));

    verifyNoInteractions(client);
  }

  @Test
  void forceClose_prefixCollision_notASubPath_rejected403() throws Exception {
    // Defense-in-depth: a sibling tenant whose id shares the caller's tenant as a string prefix
    // ("acme" vs "acme2") must NOT pass — the guard's trailing "/" makes "t-acme/" a path boundary.
    String siblingTenantId =
        WorkflowIds.position("acme2", "copytrade-v1", "AAPL260727C00330000", "sig1");
    mvc.perform(
            post("/api/positions/force-close")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflow_id\":\"" + siblingTenantId + "\",\"reason\":\"x\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("cross_tenant_workflow_id"));

    verifyNoInteractions(client);
  }

  @Test
  void forceClose_withOperatorHeader_threadsActorIntoOperatorId() throws Exception {
    // C2: an OPTIONAL X-Operator-Id (the Phase-2 dashboard sends the verified session email) is
    // threaded into the audit subject for per-human attribution: "tenant:<t>:<actor>".
    String ownWorkflowId =
        WorkflowIds.position("acme", "copytrade-v1", "AAPL260727C00330000", "sig1");
    when(stub.update(eq("force_close"), eq(ForceCloseResult.class), any()))
        .thenReturn(accepted("force:tenant:acme:1"));

    mvc.perform(
            post("/api/positions/force-close")
                .header("X-Tenant-Id", "acme")
                .header("X-Operator-Id", "alice@example.com")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflow_id\":\"" + ownWorkflowId + "\",\"reason\":\"manual exit\"}"))
        .andExpect(status().isAccepted());

    ArgumentCaptor<ForceCloseRequest> cap = ArgumentCaptor.forClass(ForceCloseRequest.class);
    org.mockito.Mockito.verify(stub)
        .update(eq("force_close"), eq(ForceCloseResult.class), cap.capture());
    assertThat(cap.getValue().getOperatorId()).isEqualTo("tenant:acme:alice@example.com");
  }

  @Test
  void forceClose_noOperatorHeader_fallsBackToTenant() throws Exception {
    // C2: no X-Operator-Id (direct callers may omit it) → plain tenant attribution.
    String ownWorkflowId =
        WorkflowIds.position("acme", "copytrade-v1", "AAPL260727C00330000", "sig1");
    when(stub.update(eq("force_close"), eq(ForceCloseResult.class), any()))
        .thenReturn(accepted("force:tenant:acme:1"));

    mvc.perform(
            post("/api/positions/force-close")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflow_id\":\"" + ownWorkflowId + "\",\"reason\":\"manual exit\"}"))
        .andExpect(status().isAccepted());

    ArgumentCaptor<ForceCloseRequest> cap = ArgumentCaptor.forClass(ForceCloseRequest.class);
    org.mockito.Mockito.verify(stub)
        .update(eq("force_close"), eq(ForceCloseResult.class), cap.capture());
    assertThat(cap.getValue().getOperatorId()).isEqualTo("tenant:acme");
  }

  @Test
  void forceClose_operatorHeaderWithJunk_sanitizedIntoOperatorId() throws Exception {
    // C2: a caller-supplied actor with unsafe chars is stripped to a conservative set before it
    // reaches the audit subject (attribution safety, not authz).
    String ownWorkflowId =
        WorkflowIds.position("acme", "copytrade-v1", "AAPL260727C00330000", "sig1");
    when(stub.update(eq("force_close"), eq(ForceCloseResult.class), any()))
        .thenReturn(accepted("force:tenant:acme:1"));

    mvc.perform(
            post("/api/positions/force-close")
                .header("X-Tenant-Id", "acme")
                .header("X-Operator-Id", "  bob<script>@e vil.com/../\n ")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflow_id\":\"" + ownWorkflowId + "\",\"reason\":\"manual exit\"}"))
        .andExpect(status().isAccepted());

    ArgumentCaptor<ForceCloseRequest> cap = ArgumentCaptor.forClass(ForceCloseRequest.class);
    org.mockito.Mockito.verify(stub)
        .update(eq("force_close"), eq(ForceCloseResult.class), cap.capture());
    // trim + strip: keeps [A-Za-z0-9_.@+-], drops <>()/ whitespace etc.
    assertThat(cap.getValue().getOperatorId()).isEqualTo("tenant:acme:bobscript@evil.com..");
  }

  @Test
  void forceClose_nonPositionWorkflowId_rejected() throws Exception {
    // C3: only PositionWorkflows are force-closable. The tenant's OWN killswitch id shares the
    // "t-acme/" prefix (passes the cross-tenant guard) but has no "/pos/" segment → rejected
    // BEFORE any Temporal call.
    String ownKillswitchId = WorkflowIds.killswitch("acme", "copytrade-v1");
    mvc.perform(
            post("/api/positions/force-close")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflow_id\":\"" + ownKillswitchId + "\",\"reason\":\"x\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("not_a_position_workflow_id"));

    verifyNoInteractions(client);
  }

  @Test
  void forceClose_blankReason_rejected400() throws Exception {
    String ownWorkflowId =
        WorkflowIds.position("acme", "copytrade-v1", "AAPL260727C00330000", "sig1");
    mvc.perform(
            post("/api/positions/force-close")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflow_id\":\"" + ownWorkflowId + "\",\"reason\":\"   \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("bad_request"));

    verifyNoInteractions(client);
  }

  @Test
  void forceClose_missingWorkflowId_rejected400() throws Exception {
    mvc.perform(
            post("/api/positions/force-close")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"x\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("bad_request"));

    verifyNoInteractions(client);
  }
}
