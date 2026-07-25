package com.ohmytradeagent.tdbff.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.contract.identity.WorkflowIds;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Dark-launch: with the force-close write flag OFF (the default), {@code POST
 * /api/positions/force-close} 404s server-side — the real-money exit surface is not merely hidden
 * in the UI. The enabled-path guards live in {@link PositionsForceCloseControllerWebMvcTest}.
 */
@WebMvcTest(PositionsController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
class PositionsForceCloseDarkLaunchTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private WorkflowClient client;
  @MockitoBean private com.ohmytradeagent.tdbff.positions.PositionsReader reader;

  @BeforeEach
  void setUp() {
    // Even a fully-valid, own-tenant request must not reach the workflow while the flag is off.
    when(client.newUntypedWorkflowStub(anyString())).thenReturn(mock(WorkflowStub.class));
  }

  @Test
  void forceClose_whenWriteFlagOff_returns404_andNeverAddressesWorkflow() throws Exception {
    String ownWorkflowId =
        WorkflowIds.position("acme", "copytrade-v1", "AAPL260727C00330000", "sig1");
    mvc.perform(
            post("/api/positions/force-close")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"workflow_id\":\""
                        + ownWorkflowId
                        + "\",\"reason\":\"operator manual exit\"}"))
        .andExpect(status().isNotFound());

    // Gated BEFORE any tenant resolution / workflow addressing.
    verify(client, never()).newUntypedWorkflowStub(anyString());
  }
}
