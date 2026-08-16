package com.ohmytradeagent.tdbff.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.contract.ArmTrailRequest;
import com.ohmytradeagent.contract.ArmTrailResult;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import io.temporal.client.WorkflowClient;
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
 * Web-layer guards for the per-position operator trailing stop, {@code POST
 * /api/positions/arm-trail} ("Stop-loss" on /live). The write flag is ON here so the guard paths
 * are exercised; the dark-launch (flag off → 404) case is in {@link
 * PositionsArmTrailDarkLaunchTest}.
 */
@WebMvcTest(PositionsController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
@TestPropertySource(properties = "positions.arm-trail.write-enabled=true")
class PositionsArmTrailControllerWebMvcTest {

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

  private static ArmTrailResult result(ArmTrailResult.Status status) {
    ArmTrailResult r = new ArmTrailResult();
    r.setSchemaVersion(1L);
    r.setStatus(status);
    return r;
  }

  @Test
  void armTrail_armed_returns202_andEchoesTheStopActuallySet() throws Exception {
    ArmTrailResult armed = result(ArmTrailResult.Status.ARMED);
    armed.setPeakPremium(new BigDecimal("2.50"));
    armed.setGivebackPct(new BigDecimal("0.15"));
    armed.setStopPrice(new BigDecimal("2.13"));
    when(stub.update(eq("arm_trail"), eq(ArmTrailResult.class), any())).thenReturn(armed);

    mvc.perform(
            post("/api/positions/arm-trail")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflow_id\":\"" + ownWorkflowId() + "\",\"giveback_pct\":0.15}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("ARMED"))
        // The response echoes what the WORKFLOW resolved, not what the client asked for — the
        // anchor is chosen server-side, so the operator must be shown the stop that now exists.
        .andExpect(jsonPath("$.peak_premium").value(2.50))
        .andExpect(jsonPath("$.stop_price").value(2.13));

    // giveback is threaded through; peak_premium is deliberately NOT client-supplied.
    ArgumentCaptor<ArmTrailRequest> sent = ArgumentCaptor.forClass(ArmTrailRequest.class);
    verify(stub).update(eq("arm_trail"), eq(ArmTrailResult.class), sent.capture());
    assertGiveback(sent.getValue());
  }

  private static void assertGiveback(ArmTrailRequest r) {
    org.assertj.core.api.Assertions.assertThat(r.getGivebackPct())
        .isEqualByComparingTo(new BigDecimal("0.15"));
    org.assertj.core.api.Assertions.assertThat(r.getPeakPremium()).isNull();
    org.assertj.core.api.Assertions.assertThat(r.getOperatorId()).contains("acme");
  }

  @Test
  void armTrail_alreadyArmed_returns200_not202() throws Exception {
    // The 202/200 split is load-bearing, not cosmetic: the dashboard branches on it so a green
    // "stop set" is never painted over a request that changed nothing.
    ArmTrailResult already = result(ArmTrailResult.Status.ALREADY_ARMED);
    already.setGivebackPct(new BigDecimal("0.10"));
    when(stub.update(eq("arm_trail"), eq(ArmTrailResult.class), any())).thenReturn(already);

    mvc.perform(
            post("/api/positions/arm-trail")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflow_id\":\"" + ownWorkflowId() + "\",\"giveback_pct\":0.25}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ALREADY_ARMED"))
        // Echoes the stop IN FORCE (the tighter 10%), not the 25% requested.
        .andExpect(jsonPath("$.giveback_pct").value(0.10));
  }

  @Test
  void armTrail_rejected_returns422_withTheReason() throws Exception {
    // THE case this endpoint exists to get right: a refused arm must NOT ride back on a 2xx, or
    // the operator is told a real-money position is protected when it is not.
    ArmTrailResult rejected = result(ArmTrailResult.Status.REJECTED);
    rejected.setReason("subscription_failed");
    when(stub.update(eq("arm_trail"), eq(ArmTrailResult.class), any())).thenReturn(rejected);

    mvc.perform(
            post("/api/positions/arm-trail")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflow_id\":\"" + ownWorkflowId() + "\",\"giveback_pct\":0.15}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.status").value("REJECTED"))
        .andExpect(jsonPath("$.reason").value("subscription_failed"));
  }

  @Test
  void armTrail_crossTenantWorkflowId_returns403_andNeverAddressesWorkflow() throws Exception {
    // The security control of this endpoint. The assertion that matters is not the status code but
    // that no Update was dispatched at another tenant's position.
    String otherTenants =
        WorkflowIds.position("globex", "copytrade-v1", "AAPL260727C00330000", "sig1");

    mvc.perform(
            post("/api/positions/arm-trail")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflow_id\":\"" + otherTenants + "\",\"giveback_pct\":0.15}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("cross_tenant_workflow_id"));

    verify(stub, never()).update(anyString(), any(), any());
  }

  @Test
  void armTrail_nonPositionWorkflowId_isRefused() throws Exception {
    // The tenant prefix alone would also admit this caller's OWN killswitch/recon workflow ids;
    // the /pos/ kind guard is what stops an arm_trail Update being aimed at one of those.
    mvc.perform(
            post("/api/positions/arm-trail")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"workflow_id\":\"t-acme/s-copytrade-v1/killswitch\",\"giveback_pct\":0.15}"))
        .andExpect(status().isForbidden());

    verify(stub, never()).update(anyString(), any(), any());
  }

  @Test
  void armTrail_givebackOutOfRange_returns400_namingTheField() throws Exception {
    // Pre-validated in the controller so an operator typo is a correctable 400 rather than the
    // workflow validator's 409 update_rejected, which reads as a system fault. 0.5 is the
    // inclusive ceiling (PositionWorkflowImpl.MAX_GIVEBACK).
    for (String bad : new String[] {"0.6", "0", "-0.1"}) {
      mvc.perform(
              post("/api/positions/arm-trail")
                  .header("X-Tenant-Id", "acme")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"workflow_id\":\"" + ownWorkflowId() + "\",\"giveback_pct\":" + bad + "}"))
          .andExpect(status().isBadRequest());
    }
    verify(stub, never()).update(anyString(), any(), any());
  }

  @Test
  void armTrail_givebackAtTheCeiling_isAccepted() throws Exception {
    // 0.5 must be INCLUSIVE — the controller bound and MAX_GIVEBACK have to agree exactly, or a
    // value the UI offers becomes a 409 at the workflow.
    when(stub.update(eq("arm_trail"), eq(ArmTrailResult.class), any()))
        .thenReturn(result(ArmTrailResult.Status.ARMED));

    mvc.perform(
            post("/api/positions/arm-trail")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflow_id\":\"" + ownWorkflowId() + "\",\"giveback_pct\":0.5}"))
        .andExpect(status().isAccepted());
  }

  @Test
  void armTrail_missingWorkflowId_returns400() throws Exception {
    mvc.perform(
            post("/api/positions/arm-trail")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"giveback_pct\":0.15}"))
        .andExpect(status().isBadRequest());

    verify(stub, never()).update(anyString(), any(), any());
  }
}
