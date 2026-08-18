package com.ohmytradeagent.tdbff.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.contract.ArmTrailResult;
import com.ohmytradeagent.contract.ForceCloseResult;
import com.ohmytradeagent.contract.PartialCloseResult;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.tdbff.positions.PositionsReader;
import com.ohmytradeagent.tdbff.positions.PositionsReader.OpenPosition;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
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
 * #718 — an operator's cached {@code workflow_id} goes dead when recon ADOPTS the position, because
 * adoption mints a new id for the same contract. Before this fix every write surface reported
 * {@code position_already_closed}: a claim that is false and reassuring while the position is still
 * open and merely untracked by the id the operator holds. Hit live 2026-08-17 on prod-kipark AMD —
 * the operator clicked Force-exit twice and the first click left no audit row at all, because the
 * audit is emitted INSIDE the workflow update handler.
 *
 * <p>The contract asserted here: a dead id is resolved to the LIVE owner of the same OCC and the
 * update retried once; {@code position_already_closed} survives only when that lookup finds
 * nothing, i.e. only when it is true.
 */
@WebMvcTest(PositionsController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
@TestPropertySource(
    properties = {
      "positions.force-close.write-enabled=true",
      "positions.partial-close.write-enabled=true",
      "positions.arm-trail.write-enabled=true"
    })
class PositionsReadoptionRetryWebMvcTest {

  private static final String OCC = "AMD   260819C00530000";

  /** The id the operator's page still holds; this workflow FAILED and was replaced by adoption. */
  private static final String STALE =
      WorkflowIds.position("acme", "copytrade-v1", OCC, "chat-77-1538925306302439515:0");

  /** The id recon minted at adoption — same tenant, same contract, different entry signal id. */
  private static final String LIVE =
      WorkflowIds.position("acme", "copytrade-v1", OCC, "chat-77-1538930696926531606:0");

  @Autowired private MockMvc mvc;
  @MockitoBean private WorkflowClient client;
  @MockitoBean private PositionsReader reader;

  private WorkflowStub deadStub;
  private WorkflowStub liveStub;

  @BeforeEach
  void setUp() {
    deadStub = mock(WorkflowStub.class);
    liveStub = mock(WorkflowStub.class);
    when(client.newUntypedWorkflowStub(eq(STALE))).thenReturn(deadStub);
    when(client.newUntypedWorkflowStub(eq(LIVE))).thenReturn(liveStub);
  }

  private static WorkflowNotFoundException dead(String wfId) {
    return new WorkflowNotFoundException(
        WorkflowExecution.newBuilder().setWorkflowId(wfId).build(), "PositionWorkflow", null);
  }

  private static OpenPosition open(String wfId, String occ) {
    return new OpenPosition(
        wfId, "copytrade-v1", occ, 12, new BigDecimal("3.66"), new BigDecimal("4392"));
  }

  private void tenantHolds(OpenPosition... positions) {
    when(reader.openPositions("acme")).thenReturn(List.of(positions));
  }

  private static ForceCloseResult accepted() {
    ForceCloseResult r = new ForceCloseResult();
    r.setSchemaVersion(1L);
    r.setStatus(ForceCloseResult.Status.ACCEPTED);
    r.setExitSignalId("force:tenant:acme:42");
    return r;
  }

  // ---- force-close ---------------------------------------------------------

  @Test
  void forceClose_staleIdAfterAdoption_retriesAgainstTheLiveOwner() throws Exception {
    when(deadStub.update(eq("force_close"), eq(ForceCloseResult.class), any()))
        .thenThrow(dead(STALE));
    when(liveStub.update(eq("force_close"), eq(ForceCloseResult.class), any()))
        .thenReturn(accepted());
    tenantHolds(open(LIVE, OCC));

    mvc.perform(
            post("/api/positions/force-close")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflow_id\":\"" + STALE + "\",\"reason\":\"manual exit\"}"))
        .andExpect(status().isAccepted());

    verify(liveStub).update(eq("force_close"), eq(ForceCloseResult.class), any());
  }

  /**
   * The position really is gone: the lookup finds no running owner for the OCC, so {@code
   * position_already_closed} is now a VERIFIED claim rather than an assumption. Pins the
   * pre-existing behaviour so the fix cannot turn a true 409 into a 500.
   */
  @Test
  void forceClose_staleId_noLiveOwner_stillReportsAlreadyClosed() throws Exception {
    when(deadStub.update(eq("force_close"), eq(ForceCloseResult.class), any()))
        .thenThrow(dead(STALE));
    tenantHolds(); // nothing open

    mvc.perform(
            post("/api/positions/force-close")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflow_id\":\"" + STALE + "\",\"reason\":\"manual exit\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("position_already_closed"));
  }

  /**
   * Two running workflows own the same contract. Retrying would pick one at random and sell the
   * wrong lot, so refuse and let the operator look — a real-money write must not guess.
   */
  @Test
  void forceClose_ambiguousOwners_refusesRatherThanGuessing() throws Exception {
    String other = WorkflowIds.position("acme", "copytrade-v1", OCC, "chat-77-999:0");
    WorkflowStub otherStub = mock(WorkflowStub.class);
    when(client.newUntypedWorkflowStub(eq(other))).thenReturn(otherStub);
    when(deadStub.update(eq("force_close"), eq(ForceCloseResult.class), any()))
        .thenThrow(dead(STALE));
    tenantHolds(open(LIVE, OCC), open(other, OCC));

    mvc.perform(
            post("/api/positions/force-close")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflow_id\":\"" + STALE + "\",\"reason\":\"manual exit\"}"))
        .andExpect(status().isConflict());

    verify(liveStub, never()).update(any(), any(), any());
    verify(otherStub, never()).update(any(), any(), any());
  }

  /** A different contract is NOT this position; it must never be retried against. */
  @Test
  void forceClose_onlyADifferentContractIsOpen_doesNotRetryAgainstIt() throws Exception {
    String otherOcc = "NVDA  260821C00180000";
    String otherWf = WorkflowIds.position("acme", "copytrade-v1", otherOcc, "chat-77-555:0");
    WorkflowStub otherStub = mock(WorkflowStub.class);
    when(client.newUntypedWorkflowStub(eq(otherWf))).thenReturn(otherStub);
    when(deadStub.update(eq("force_close"), eq(ForceCloseResult.class), any()))
        .thenThrow(dead(STALE));
    tenantHolds(open(otherWf, otherOcc));

    mvc.perform(
            post("/api/positions/force-close")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflow_id\":\"" + STALE + "\",\"reason\":\"manual exit\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("position_already_closed"));

    verify(otherStub, never()).update(any(), any(), any());
  }

  // ---- partial-close (Trim) ------------------------------------------------

  @Test
  void partialClose_staleIdAfterAdoption_retriesAgainstTheLiveOwner() throws Exception {
    PartialCloseResult r = new PartialCloseResult();
    r.setSchemaVersion(1L);
    r.setStatus(PartialCloseResult.Status.ACCEPTED);
    when(deadStub.update(eq("partial_close"), eq(PartialCloseResult.class), any()))
        .thenThrow(dead(STALE));
    when(liveStub.update(eq("partial_close"), eq(PartialCloseResult.class), any())).thenReturn(r);
    tenantHolds(open(LIVE, OCC));

    mvc.perform(
            post("/api/positions/partial-close")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"workflow_id\":\"" + STALE + "\",\"reason\":\"trim\",\"fraction\":0.5}"))
        .andExpect(status().isAccepted());

    verify(liveStub).update(eq("partial_close"), eq(PartialCloseResult.class), any());
  }

  // ---- arm-trail (stop loss) -----------------------------------------------

  @Test
  void armTrail_staleIdAfterAdoption_retriesAgainstTheLiveOwner() throws Exception {
    ArmTrailResult r = new ArmTrailResult();
    r.setSchemaVersion(1L);
    r.setStatus(ArmTrailResult.Status.ARMED);
    when(deadStub.update(eq("arm_trail"), eq(ArmTrailResult.class), any())).thenThrow(dead(STALE));
    when(liveStub.update(eq("arm_trail"), eq(ArmTrailResult.class), any())).thenReturn(r);
    tenantHolds(open(LIVE, OCC));

    mvc.perform(
            post("/api/positions/arm-trail")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflow_id\":\"" + STALE + "\",\"giveback_pct\":0.45}"))
        .andExpect(status().is2xxSuccessful());

    verify(liveStub).update(eq("arm_trail"), eq(ArmTrailResult.class), any());
  }
}
