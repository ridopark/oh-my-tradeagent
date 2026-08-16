package com.ohmytradeagent.tdbff.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.contract.identity.WorkflowIds;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import java.util.List;
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
 * The OFF switch. Unlike its two siblings the arm-trail flag ships ENABLED, so this pins the
 * DISABLE path rather than a dark launch: with {@code positions.arm-trail.write-enabled=false} (set
 * explicitly here, since it is no longer the default) {@code POST /api/positions/arm-trail} 404s
 * server-side. That path matters MORE now the flag is normally on — it is the one an operator
 * reaches for in a hurry, so it must be known to work rather than assumed. The other two flags are
 * ON here so all three are proven INDEPENDENT in both directions.
 *
 * <p>Also pins the reason the flag is checked IN-METHOD rather than with
 * {@code @ConditionalOnProperty}: that annotation would remove the whole controller bean, taking
 * {@code GET /api/positions} and both sibling writes down with it. The GET assertion below is what
 * would fail if someone "simplified" the gate to an annotation.
 */
@WebMvcTest(PositionsController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
@TestPropertySource(
    properties = {
      // Explicitly OFF — arm-trail now defaults to ON, so the disable path must be stated.
      "positions.arm-trail.write-enabled=false",
      "positions.force-close.write-enabled=true",
      "positions.partial-close.write-enabled=true"
    })
class PositionsArmTrailDarkLaunchTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private WorkflowClient client;
  @MockitoBean private com.ohmytradeagent.tdbff.positions.PositionsReader reader;

  @BeforeEach
  void setUp() {
    // Even a fully-valid, own-tenant request must not reach the workflow while the flag is off.
    when(client.newUntypedWorkflowStub(anyString())).thenReturn(mock(WorkflowStub.class));
  }

  @Test
  void armTrail_whenExplicitlyDisabled_returns404_andNeverAddressesWorkflow() throws Exception {
    String ownWorkflowId =
        WorkflowIds.position("acme", "copytrade-v1", "AAPL260727C00330000", "sig1");

    mvc.perform(
            post("/api/positions/arm-trail")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflow_id\":\"" + ownWorkflowId + "\",\"giveback_pct\":0.15}"))
        .andExpect(status().isNotFound())
        // The disabled state is self-describing JSON (the dashboard client parses the body).
        .andExpect(jsonPath("$.error").value("arm_trail_disabled"));

    // Gated BEFORE any tenant resolution / workflow addressing.
    verify(client, never()).newUntypedWorkflowStub(anyString());
  }

  @Test
  void positionsRead_staysAvailableWhileArmTrailIsDisabled() throws Exception {
    // The whole point of gating in-method: turning the stop-loss write off must not take the
    // holdings table down with it.
    when(reader.openPositions(anyString())).thenReturn(List.of());

    mvc.perform(get("/api/positions").header("X-Tenant-Id", "acme")).andExpect(status().isOk());
  }
}
