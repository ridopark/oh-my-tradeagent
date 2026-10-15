package com.ohmytradeagent.tdbff.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.contract.AdoptionResult;
import com.ohmytradeagent.contract.AdoptionWorkflowInput;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.tdbff.live.OpenOccCache;
import com.ohmytradeagent.tdbff.platform.StrategyConfigReader;
import com.ohmytradeagent.tdbff.portfolio.PortfolioCache;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

/** The /live Adopt button's server side. The flag-off (404) case is in the dark-launch test. */
@WebMvcTest(PositionAdoptController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
@TestPropertySource(properties = "positions.adopt.write-enabled=true")
class PositionAdoptControllerWebMvcTest {

  private static final String OCC = "NVDA261016C00225000";
  private static final String BODY = "{\"occ\":\"" + OCC + "\",\"strategy_id\":\"copytrade-v1\"}";

  @Autowired private MockMvc mvc;
  @MockitoBean private WorkflowClient client;
  @MockitoBean private StrategyConfigReader strategyConfigs;
  @MockitoBean private PortfolioCache portfolioCache;
  @MockitoBean private OpenOccCache openOccCache;

  private WorkflowStub stub;

  @BeforeEach
  void setUp() {
    stub = mock(WorkflowStub.class);
    when(client.newUntypedWorkflowStub(anyString(), any(WorkflowOptions.class))).thenReturn(stub);
    when(strategyConfigs.configsForTenant("acme"))
        .thenReturn(List.of(Map.of("strategy_id", "copytrade-v1")));
  }

  private static AdoptionResult result(AdoptionResult.Outcome outcome, String reason) {
    AdoptionResult r = new AdoptionResult();
    r.setSchemaVersion(1L);
    r.setOutcome(outcome);
    r.setReason(reason);
    if (outcome == AdoptionResult.Outcome.ADOPTED) {
      r.setWorkflowId("t-acme/s-copytrade-v1/pos/NVDA  261016C00225000/adopt:run-1");
      r.setQty(10L);
    }
    return r;
  }

  private void answers(AdoptionResult r) throws Exception {
    when(stub.getResult(anyLong(), eq(TimeUnit.SECONDS), eq(AdoptionResult.class))).thenReturn(r);
  }

  private org.springframework.test.web.servlet.ResultActions postAdopt(String body)
      throws Exception {
    return mvc.perform(
        post("/api/positions/adopt")
            .header("X-Tenant-Id", "acme")
            .header("X-Operator-Id", "ops@example.com")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  @Test
  void adopted_startsTheOperatorAdoption_allowingAnUnrecordedLot() throws Exception {
    answers(result(AdoptionResult.Outcome.ADOPTED, null));

    postAdopt(BODY)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("ADOPTED"))
        .andExpect(jsonPath("$.qty").value(10));

    ArgumentCaptor<WorkflowOptions> opts = ArgumentCaptor.forClass(WorkflowOptions.class);
    verify(client).newUntypedWorkflowStub(eq("AdoptionWorkflow"), opts.capture());
    assertThat(opts.getValue().getWorkflowId())
        .isEqualTo(WorkflowIds.adoption("acme", "copytrade-v1", OCC));
    // A finished earlier attempt (a refusal) must not block a retry; a RUNNING one still does.
    assertThat(opts.getValue().getWorkflowIdReusePolicy())
        .isEqualTo(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE);
    ArgumentCaptor<Object> in = ArgumentCaptor.forClass(Object.class);
    verify(stub).start(in.capture());
    AdoptionWorkflowInput input = (AdoptionWorkflowInput) in.getValue();
    assertThat(input.getTenantId()).isEqualTo("acme");
    assertThat(input.getStrategyId()).isEqualTo("copytrade-v1");
    assertThat(input.getOcc()).isEqualTo(OCC);
    assertThat(input.getAllowUnrecorded()).isTrue();
    assertThat(input.getOperatorId()).isEqualTo("tenant:acme:ops@example.com");
    verify(portfolioCache).invalidate("acme");
    verify(openOccCache).invalidate("acme");
  }

  @Test
  void refusedUnsafe_is409_withTheReason_andLeavesTheCachesAlone() throws Exception {
    answers(result(AdoptionResult.Outcome.REFUSED_UNSAFE, "open_sell_order"));

    postAdopt(BODY)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.outcome").value("REFUSED_UNSAFE"))
        .andExpect(jsonPath("$.reason").value("open_sell_order"));
    verify(portfolioCache, never()).invalidate(anyString());
  }

  @Test
  void alreadyOwned_is200() throws Exception {
    answers(result(AdoptionResult.Outcome.ALREADY_OWNED, null));

    postAdopt(BODY)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("ALREADY_OWNED"));
  }

  @Test
  void doubleClick_attachesToTheRunningAdoption() throws Exception {
    doThrow(
            new WorkflowExecutionAlreadyStarted(
                WorkflowExecution.getDefaultInstance(), "AdoptionWorkflow", null))
        .when(stub)
        .start(any());
    WorkflowStub running = mock(WorkflowStub.class);
    when(client.newUntypedWorkflowStub(WorkflowIds.adoption("acme", "copytrade-v1", OCC)))
        .thenReturn(running);
    when(running.getResult(anyLong(), eq(TimeUnit.SECONDS), eq(AdoptionResult.class)))
        .thenReturn(result(AdoptionResult.Outcome.ADOPTED, null));

    postAdopt(BODY).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("ADOPTED"));
  }

  @Test
  void slowAdoption_is202Pending_andIsNeverCancelled() throws Exception {
    // Cancelling between the owner's start and its fill forward would strand the owner.
    when(stub.getResult(anyLong(), eq(TimeUnit.SECONDS), eq(AdoptionResult.class)))
        .thenThrow(new TimeoutException("slow"));

    postAdopt(BODY)
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.outcome").value("PENDING"));
    verify(stub, never()).cancel();
    verify(stub, never()).terminate(anyString());
  }

  @Test
  void strategyTheTenantDoesNotOwn_is403_withoutStartingAnything() throws Exception {
    postAdopt("{\"occ\":\"" + OCC + "\",\"strategy_id\":\"someone-elses\"}")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("unknown_strategy"));
    verify(stub, never()).start(any());
  }

  @Test
  void malformedOcc_is400() throws Exception {
    postAdopt("{\"occ\":\"NVDA\",\"strategy_id\":\"copytrade-v1\"}")
        .andExpect(status().isBadRequest());
    verify(stub, never()).start(any());
  }

  @Test
  void missingStrategy_is400() throws Exception {
    postAdopt("{\"occ\":\"" + OCC + "\"}").andExpect(status().isBadRequest());
    verify(stub, never()).start(any());
  }

  @Test
  void missingTenant_is401() throws Exception {
    mvc.perform(post("/api/positions/adopt").contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isUnauthorized());
    verify(stub, never()).start(any());
  }
}
