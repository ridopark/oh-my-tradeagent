package com.ohmytradeagent.tdbff.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.contract.ArmTrailResult;
import com.ohmytradeagent.contract.CopytradeEntryStatus;
import com.ohmytradeagent.contract.ForceCloseResult;
import com.ohmytradeagent.contract.PartialCloseResult;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.tdbff.live.OpenOccCache;
import com.ohmytradeagent.tdbff.platform.StrategyConfigReader;
import com.ohmytradeagent.tdbff.portfolio.PortfolioCache;
import com.ohmytradeagent.tdbff.positions.PositionsReader;
import com.ohmytradeagent.tdbff.proximity.MarketDataQuoteClient;
import com.ohmytradeagent.tdbff.proximity.MarketDataQuoteClient.OptionQuote;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
 * Every operator write that changes what /live shows drops the tenant's read caches — but ONLY when
 * the write succeeded, so a refused or failed action leaves the caches (and its own response)
 * exactly as before.
 */
@WebMvcTest({PositionsController.class, ManualEntryController.class})
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
@TestPropertySource(
    properties = {
      "positions.force-close.write-enabled=true",
      "positions.partial-close.write-enabled=true",
      "positions.arm-trail.write-enabled=true",
      "entries.manual.write-enabled=true"
    })
class LiveCacheInvalidationWebMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private WorkflowClient client;
  @MockitoBean private PositionsReader reader;
  @MockitoBean private MarketDataQuoteClient quotes;
  @MockitoBean private StrategyConfigReader strategyConfigs;
  @MockitoBean private PortfolioCache portfolioCache;
  @MockitoBean private OpenOccCache openOccCache;

  private WorkflowStub stub;

  @BeforeEach
  void setUp() {
    stub = mock(WorkflowStub.class);
    when(client.newUntypedWorkflowStub(anyString())).thenReturn(stub);
    when(client.newUntypedWorkflowStub(anyString(), any(WorkflowOptions.class))).thenReturn(stub);
    when(reader.openPositions("acme")).thenReturn(List.of());
    when(quotes.optionQuote(anyString()))
        .thenReturn(
            new OptionQuote(
                new BigDecimal("2.30"), new BigDecimal("2.33"), new BigDecimal("2.35")));
    when(strategyConfigs.configsForTenant("acme"))
        .thenReturn(List.of(Map.of("strategy_id", "copytrade-v1")));
  }

  private static String positionWorkflowId() {
    return WorkflowIds.position("acme", "copytrade-v1", "AAPL260727C00330000", "sig1");
  }

  private void postJson(String path, String json, int expectedStatus) throws Exception {
    mvc.perform(
            post(path)
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
        .andExpect(status().is(expectedStatus));
  }

  private String closeBody(String extra) {
    return "{\"workflow_id\":\"" + positionWorkflowId() + "\",\"reason\":\"exit\"" + extra + "}";
  }

  private void workflowGone() {
    WorkflowExecution exec =
        WorkflowExecution.newBuilder().setWorkflowId(positionWorkflowId()).build();
    when(stub.update(anyString(), any(), any()))
        .thenThrow(new WorkflowNotFoundException(exec, "PositionWorkflow", null));
  }

  // ---------- force-close ----------

  @Test
  void forceClose_success_invalidatesPortfolioAndOccSet() throws Exception {
    ForceCloseResult r = new ForceCloseResult();
    r.setStatus(ForceCloseResult.Status.ACCEPTED);
    when(stub.update(eq("force_close"), eq(ForceCloseResult.class), any())).thenReturn(r);

    postJson("/api/positions/force-close", closeBody(""), 202);

    verify(portfolioCache).invalidate("acme");
    verify(openOccCache).invalidate("acme");
  }

  @Test
  void forceClose_failure_invalidatesNothing() throws Exception {
    workflowGone();

    postJson("/api/positions/force-close", closeBody(""), 409);

    verifyNoInteractions(portfolioCache, openOccCache);
  }

  // ---------- partial-close (Trim) ----------

  @Test
  void partialClose_success_invalidatesPortfolio() throws Exception {
    PartialCloseResult r = new PartialCloseResult();
    r.setStatus(PartialCloseResult.Status.ACCEPTED);
    when(stub.update(eq("partial_close"), eq(PartialCloseResult.class), any())).thenReturn(r);

    postJson("/api/positions/partial-close", closeBody(",\"fraction\":0.5"), 202);

    verify(portfolioCache).invalidate("acme");
  }

  @Test
  void partialClose_failure_invalidatesNothing() throws Exception {
    workflowGone();

    postJson("/api/positions/partial-close", closeBody(",\"fraction\":0.5"), 409);

    verifyNoInteractions(portfolioCache, openOccCache);
  }

  // ---------- arm-trail (Stop-loss) ----------

  private void armTrailReturns(ArmTrailResult.Status s) {
    ArmTrailResult r = new ArmTrailResult();
    r.setStatus(s);
    when(stub.update(eq("arm_trail"), eq(ArmTrailResult.class), any())).thenReturn(r);
  }

  private static String armBody() {
    return "{\"workflow_id\":\"" + positionWorkflowId() + "\",\"giveback_pct\":0.15}";
  }

  @Test
  void armTrail_armed_invalidatesPortfolio() throws Exception {
    armTrailReturns(ArmTrailResult.Status.ARMED);

    postJson("/api/positions/arm-trail", armBody(), 202);

    verify(portfolioCache).invalidate("acme");
  }

  @Test
  void armTrail_rejected_invalidatesNothing() throws Exception {
    armTrailReturns(ArmTrailResult.Status.REJECTED);

    postJson("/api/positions/arm-trail", armBody(), 422);

    verifyNoInteractions(portfolioCache, openOccCache);
  }

  // ---------- manual entry ----------

  private static String entryBody() {
    return "{\"occ\":\"NVDA 260821C00225000\",\"strategy_id\":\"copytrade-v1\",\"qty\":1,"
        + "\"quoted_ask\":2.35,\"quoted_at\":\""
        + OffsetDateTime.now(ZoneOffset.UTC)
        + "\",\"idempotency_key\":\"idem-1\"}";
  }

  @Test
  void manualEntry_started_invalidatesPortfolioAndOccSet() throws Exception {
    postJson("/api/entries/manual", entryBody(), 202);

    verify(portfolioCache).invalidate("acme");
    verify(openOccCache).invalidate("acme");
  }

  @Test
  void manualEntry_duplicate_invalidatesNothing() throws Exception {
    doThrow(
            new WorkflowExecutionAlreadyStarted(
                WorkflowExecution.newBuilder().setWorkflowId("x").build(),
                "CopytradeSignalWorkflow",
                null))
        .when(stub)
        .start(any());

    postJson("/api/entries/manual", entryBody(), 409);

    verifyNoInteractions(portfolioCache, openOccCache);
  }

  private void entryStatusIs(CopytradeEntryStatus.State state) throws Exception {
    entryStatusIs("manual:idem-1", state);
  }

  // The controller (and its once-per-fill memory) is shared across tests in this context, so a test
  // that depends on that memory uses its own signal id.
  private void entryStatusIs(String signalId, CopytradeEntryStatus.State state) throws Exception {
    CopytradeEntryStatus s = new CopytradeEntryStatus();
    s.setState(state);
    when(stub.query("entryStatus", CopytradeEntryStatus.class)).thenReturn(s);
    mvc.perform(
            get("/api/entries/" + signalId + "/status")
                .param("strategy_id", "copytrade-v1")
                .header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk());
  }

  @Test
  void manualEntryStatus_filled_invalidatesPortfolioAndOccSet() throws Exception {
    entryStatusIs(CopytradeEntryStatus.State.FILLED);

    verify(portfolioCache).invalidate("acme");
    verify(openOccCache).invalidate("acme");
  }

  @Test
  void manualEntryStatus_filledPolledRepeatedly_invalidatesOnce() throws Exception {
    // The status endpoint is a poll: repeat GETs on an already-filled entry must not keep flushing
    // the tenant's cache (each flush sends the next portfolio read back to the broker).
    entryStatusIs("manual:idem-repeat", CopytradeEntryStatus.State.FILLED);
    entryStatusIs("manual:idem-repeat", CopytradeEntryStatus.State.FILLED);
    entryStatusIs("manual:idem-repeat", CopytradeEntryStatus.State.FILLED);

    verify(portfolioCache, times(1)).invalidate("acme");
    verify(openOccCache, times(1)).invalidate("acme");
  }

  @Test
  void manualEntryStatus_notYetFilled_invalidatesNothing() throws Exception {
    entryStatusIs(CopytradeEntryStatus.State.SUBMITTED);

    verifyNoInteractions(portfolioCache, openOccCache);
  }
}
