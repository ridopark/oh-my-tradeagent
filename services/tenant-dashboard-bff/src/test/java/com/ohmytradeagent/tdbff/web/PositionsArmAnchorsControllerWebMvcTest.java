package com.ohmytradeagent.tdbff.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.tdbff.positions.TradeContextPeakReader;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer contract of {@code GET /api/positions/arm-anchors} (#778): both candidate anchors for
 * the /live arm control, with the true-peak side fail-soft. The reader's own three-state behavior
 * (value / missing TABLE / missing row, all non-throwing) is pinned in {@code
 * TradeContextPeakReaderTest}; here the reader is mocked and the null-anchor rendering, guards and
 * stop math are the subject.
 */
@WebMvcTest(PositionsArmAnchorsController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
class PositionsArmAnchorsControllerWebMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private TradeContextPeakReader peaks;

  private static String ownWorkflowId() {
    return WorkflowIds.position("acme", "copytrade-v1", "AAPL260727C00330000", "sig1");
  }

  @Test
  void truePeakAvailable_returnsBothAnchors_withCentRoundedStop() throws Exception {
    when(peaks.mfePremium(eq("acme"), eq(ownWorkflowId()))).thenReturn(new BigDecimal("3.40"));

    mvc.perform(
            get("/api/positions/arm-anchors")
                .header("X-Tenant-Id", "acme")
                .param("workflow_id", ownWorkflowId())
                .param("giveback_pct", "0.35"))
        .andExpect(status().isOk())
        // Recent = today's behavior: the WORKFLOW resolves it when peak_premium is omitted, so the
        // BFF must not invent a number for it.
        .andExpect(jsonPath("$.recent_anchor.peak").value((Object) null))
        .andExpect(jsonPath("$.recent_anchor.stop").value((Object) null))
        .andExpect(jsonPath("$.recent_anchor.source").value("workflow_resolved"))
        // 3.40 * (1 - 0.35) = 2.21 exactly; the TSLA 260918P case from the issue.
        .andExpect(jsonPath("$.true_peak_anchor.peak").value(3.40))
        .andExpect(jsonPath("$.true_peak_anchor.stop").value(2.21))
        .andExpect(jsonPath("$.true_peak_anchor.source").value("trade_context_mfe"));
  }

  @Test
  void noUsablePeak_returnsNullTruePeakAnchor_andStillOk() throws Exception {
    // The reader returns null for EVERY degraded state (datasource off, table absent while #786 is
    // unmerged, row absent, mfe null/<=0). The endpoint must render that as an explicit null —
    // today's UI — not an error.
    when(peaks.mfePremium(anyString(), anyString())).thenReturn(null);

    mvc.perform(
            get("/api/positions/arm-anchors")
                .header("X-Tenant-Id", "acme")
                .param("workflow_id", ownWorkflowId())
                .param("giveback_pct", "0.35"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.true_peak_anchor").value((Object) null))
        .andExpect(jsonPath("$.recent_anchor.source").value("workflow_resolved"));
  }

  @Test
  void givebackOutOfBounds_isRejected400_beforeAnyRead() throws Exception {
    mvc.perform(
            get("/api/positions/arm-anchors")
                .header("X-Tenant-Id", "acme")
                .param("workflow_id", ownWorkflowId())
                .param("giveback_pct", "0.6"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/api/positions/arm-anchors")
                .header("X-Tenant-Id", "acme")
                .param("workflow_id", ownWorkflowId())
                .param("giveback_pct", "0"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(peaks);
  }

  @Test
  void crossTenantWorkflowId_isRefused403_andPeakNeverRead() throws Exception {
    String foreign = WorkflowIds.position("acme2", "copytrade-v1", "AAPL260727C00330000", "sig1");
    mvc.perform(
            get("/api/positions/arm-anchors")
                .header("X-Tenant-Id", "acme")
                .param("workflow_id", foreign)
                .param("giveback_pct", "0.35"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("cross_tenant_workflow_id"));
    verifyNoInteractions(peaks);
  }

  @Test
  void missingTenantHeader_is401() throws Exception {
    mvc.perform(
            get("/api/positions/arm-anchors")
                .param("workflow_id", ownWorkflowId())
                .param("giveback_pct", "0.35"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(peaks);
  }
}
