package com.ohmytradeagent.tdbff.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Dark-launch: with the partial-close write flag OFF (the default), {@code POST
 * /api/positions/partial-close} 404s server-side. Force-close is enabled here to prove the two
 * flags are INDEPENDENT — arming the full-exit capability must not silently arm the trim. The
 * enabled-path guards live in {@link PositionsPartialCloseControllerWebMvcTest}.
 */
@WebMvcTest(PositionsController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
@TestPropertySource(properties = "positions.force-close.write-enabled=true")
class PositionsPartialCloseDarkLaunchTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private WorkflowClient client;
  @MockitoBean private com.ohmytradeagent.tdbff.positions.PositionsReader reader;

  @BeforeEach
  void setUp() {
    // Even a fully-valid, own-tenant request must not reach the workflow while the flag is off.
    when(client.newUntypedWorkflowStub(anyString())).thenReturn(mock(WorkflowStub.class));
  }

  @Test
  void partialClose_whenWriteFlagOff_returns404_andNeverAddressesWorkflow() throws Exception {
    String ownWorkflowId =
        WorkflowIds.position("acme", "copytrade-v1", "AAPL260727C00330000", "sig1");
    mvc.perform(
            post("/api/positions/partial-close")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"workflow_id\":\""
                        + ownWorkflowId
                        + "\",\"reason\":\"operator trim\",\"fraction\":0.5}"))
        .andExpect(status().isNotFound())
        // The disabled state is self-describing JSON (the dashboard client parses the body).
        .andExpect(jsonPath("$.error").value("partial_close_disabled"));

    // Gated BEFORE any tenant resolution / workflow addressing.
    verify(client, never()).newUntypedWorkflowStub(anyString());
  }
}
