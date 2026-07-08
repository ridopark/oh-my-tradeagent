package com.ohmytradeagent.tdbff.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * Dark-launch: with the reset write flag OFF (the default), {@code POST /reset} 404s server-side —
 * the write surface is not merely hidden in the UI. The enabled-path guards live in {@link
 * AccountKillSwitchControllerWebMvcTest}.
 */
@WebMvcTest(AccountKillSwitchController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
class AccountKillSwitchResetDarkLaunchTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private WorkflowClient client;

  @BeforeEach
  void setUp() {
    // Even a fully-valid request must not reach the workflow while the flag is off.
    when(client.newUntypedWorkflowStub(anyString())).thenReturn(mock(WorkflowStub.class));
  }

  @Test
  void reset_whenWriteFlagOff_returns404_andNeverAddressesWorkflow() throws Exception {
    mvc.perform(
            post("/api/account-killswitch/reset")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isNotFound());

    // Gated BEFORE any tenant resolution / workflow addressing.
    verify(client, never()).newUntypedWorkflowStub(anyString());
  }
}
