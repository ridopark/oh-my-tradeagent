package com.ohmytradeagent.tdbff.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.contract.CopytradeSignalPayload;
import com.ohmytradeagent.tdbff.platform.StrategyConfigReader;
import com.ohmytradeagent.tdbff.proximity.MarketDataQuoteClient;
import com.ohmytradeagent.tdbff.proximity.MarketDataQuoteClient.OptionQuote;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
 * PLAN-2026-08-10-live-manual-bto: web-layer guards for the operator manual-entry routes. The write
 * flag is ON here so the guard paths are exercised; the dark-launch (flag off → 404) case is in
 * {@link ManualEntryDarkLaunchTest}.
 *
 * <p>The single most load-bearing assertion in this file is {@link
 * #manual_startsCopytradeSignalWorkflowWithASyntheticBtoPayload()} — it pins the synthetic payload
 * field by field. That payload IS the contract between this service and the orchestrator's entry
 * path: get {@code source} wrong and a manual entry can auto-flatten a Discord leg; get {@code
 * tail} wrong and it trips the scale-in/de-risk cue matchers; get {@code price} wrong and the
 * marketable limit is anchored on a stale number.
 */
@WebMvcTest(ManualEntryController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
@TestPropertySource(properties = "entries.manual.write-enabled=true")
class ManualEntryControllerWebMvcTest {

  private static final String OCC = "NVDA 260821C00225000";
  private static final String CANONICAL_OCC = "NVDA  260821C00225000";

  @Autowired private MockMvc mvc;
  @MockitoBean private WorkflowClient client;
  @MockitoBean private MarketDataQuoteClient quotes;
  @MockitoBean private StrategyConfigReader strategyConfigs;

  private WorkflowStub stub;

  @BeforeEach
  void setUp() {
    stub = mock(WorkflowStub.class);
    when(client.newUntypedWorkflowStub(anyString(), any(WorkflowOptions.class))).thenReturn(stub);
    when(quotes.optionQuote(anyString()))
        .thenReturn(
            new OptionQuote(
                new BigDecimal("2.30"), new BigDecimal("2.33"), new BigDecimal("2.35")));
    when(strategyConfigs.configsForTenant("acme"))
        .thenReturn(List.of(Map.of("strategy_id", "copytrade-v1")));
  }

  private static String body(String quotedAt, String quotedAsk, int qty, String strategyId) {
    return "{\"occ\":\""
        + OCC
        + "\",\"strategy_id\":\""
        + strategyId
        + "\",\"qty\":"
        + qty
        + ",\"quoted_ask\":"
        + quotedAsk
        + ",\"quoted_at\":\""
        + quotedAt
        + "\",\"idempotency_key\":\"idem-1\"}";
  }

  private static String freshBody() {
    return body(OffsetDateTime.now(ZoneOffset.UTC).toString(), "2.35", 3, "copytrade-v1");
  }

  // ---------- quote ----------

  @Test
  void quote_returnsParsedContractAndNbbo() throws Exception {
    mvc.perform(get("/api/entries/quote").param("occ", OCC).header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.occ").value(CANONICAL_OCC))
        .andExpect(jsonPath("$.underlying").value("NVDA"))
        .andExpect(jsonPath("$.expiry").value("2026-08-21"))
        .andExpect(jsonPath("$.strike").value(225))
        .andExpect(jsonPath("$.right").value("C"))
        .andExpect(jsonPath("$.bid").value(2.30))
        .andExpect(jsonPath("$.ask").value(2.35));
  }

  @Test
  void quote_malformedOcc_is400() throws Exception {
    mvc.perform(get("/api/entries/quote").param("occ", "NOTANOCC").header("X-Tenant-Id", "acme"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_occ"));
  }

  @Test
  void quote_noAsk_is503_ratherThanAHalfPopulatedConfirmStep() throws Exception {
    when(quotes.optionQuote(anyString()))
        .thenReturn(new OptionQuote(new BigDecimal("2.30"), null, null));

    mvc.perform(get("/api/entries/quote").param("occ", OCC).header("X-Tenant-Id", "acme"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("quote_unavailable"));
  }

  @Test
  void quote_missingTenantHeader_is401() throws Exception {
    mvc.perform(get("/api/entries/quote").param("occ", OCC)).andExpect(status().isUnauthorized());
  }

  // ---------- manual entry ----------

  @Test
  void manual_startsCopytradeSignalWorkflowWithASyntheticBtoPayload() throws Exception {
    mvc.perform(
            post("/api/entries/manual")
                .header("X-Tenant-Id", "acme")
                .header("X-Operator-Id", "ridopark@gmail.com")
                .contentType(MediaType.APPLICATION_JSON)
                .content(freshBody()))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.signal_id").value("manual:idem-1"))
        .andExpect(jsonPath("$.workflow_id").value("t-acme/s-copytrade-v1/sig/manual:idem-1"))
        .andExpect(jsonPath("$.anchor_ask").value(2.35));

    ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
    verify(stub).start(payloadCaptor.capture());
    CopytradeSignalPayload p = (CopytradeSignalPayload) payloadCaptor.getValue();

    assertThat(p.getAction()).isEqualTo(CopytradeSignalPayload.Action.BTO);
    assertThat(p.getTenantId()).isEqualTo("acme");
    assertThat(p.getStrategyId()).isEqualTo("copytrade-v1");
    assertThat(p.getSignalId()).isEqualTo("manual:idem-1");
    assertThat(p.getTicker()).isEqualTo("NVDA");
    assertThat(p.getExpiry()).hasToString("2026-08-21");
    assertThat(p.getStrike()).isEqualByComparingTo("225");
    assertThat(p.getRight()).isEqualTo(CopytradeSignalPayload.Right.C);
    // The anchor is the FRESH ask, which BtoPricing turns into the marketable limit.
    assertThat(p.getPrice()).isEqualByComparingTo("2.35");
    // source=manual is what suppresses the edited-signal supersede downstream.
    assertThat(p.getSource()).isEqualTo(CopytradeSignalPayload.Source.MANUAL);
    assertThat(p.getQtyOverride()).isEqualTo(3L);
    // EMPTY tail: a non-empty one feeds the scale-in / de-risk cue matchers.
    assertThat(p.getTail()).isEmpty();
    // Operator attribution flows into the author + raw_line for the audit trail.
    assertThat(p.getAuthor()).isEqualTo("tenant:acme:ridopark@gmail.com");
    assertThat(p.getRawLine()).contains("MANUAL BTO", CANONICAL_OCC, "qty=3");
  }

  @Test
  void manual_withoutOperatorHeader_attributesToTheTenant() throws Exception {
    mvc.perform(
            post("/api/entries/manual")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(freshBody()))
        .andExpect(status().isAccepted());

    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(stub).start(captor.capture());
    assertThat(((CopytradeSignalPayload) captor.getValue()).getAuthor()).isEqualTo("tenant:acme");
  }

  @Test
  void manual_unknownStrategy_is403AndStartsNothing() throws Exception {
    mvc.perform(
            post("/api/entries/manual")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    body(
                        OffsetDateTime.now(ZoneOffset.UTC).toString(),
                        "2.35",
                        3,
                        "someone-elses-strategy")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("unknown_strategy"));

    verify(stub, never()).start(any());
  }

  @Test
  void manual_staleQuote_is409AndStartsNothing() throws Exception {
    String old = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5).toString();

    mvc.perform(
            post("/api/entries/manual")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(old, "2.35", 3, "copytrade-v1")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("quote_stale"));

    verify(stub, never()).start(any());
  }

  @Test
  void manual_askRanAwayFromTheConfirmedPrice_is409AndStartsNothing() throws Exception {
    // Operator confirmed 2.00; the market is now 2.35 — a 17.5% gap, past the 10% tolerance.
    mvc.perform(
            post("/api/entries/manual")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    body(OffsetDateTime.now(ZoneOffset.UTC).toString(), "2.00", 3, "copytrade-v1")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("quote_moved"))
        .andExpect(jsonPath("$.confirmed_ask").value(2.00))
        .andExpect(jsonPath("$.current_ask").value(2.35));

    verify(stub, never()).start(any());
  }

  @Test
  void manual_askMovedDown_stillFillsBecauseThatIsABetterBuy() throws Exception {
    // Confirmed 3.00, market now 2.35. A cheaper ask is never a reason to refuse a BUY.
    mvc.perform(
            post("/api/entries/manual")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    body(OffsetDateTime.now(ZoneOffset.UTC).toString(), "3.00", 3, "copytrade-v1")))
        .andExpect(status().isAccepted());

    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(stub).start(captor.capture());
    // …and the anchor is the CURRENT ask, not the confirmed one.
    assertThat(((CopytradeSignalPayload) captor.getValue()).getPrice())
        .isEqualByComparingTo("2.35");
  }

  @Test
  void manual_quoteUnavailableAtSubmit_is503AndStartsNothing() throws Exception {
    when(quotes.optionQuote(anyString())).thenReturn(null);

    mvc.perform(
            post("/api/entries/manual")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(freshBody()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("quote_unavailable"));

    verify(stub, never()).start(any());
  }

  @Test
  void manual_duplicateSubmission_is409() throws Exception {
    // The dedupe that stops a double-click opening a SECOND real-money position.
    org.mockito.Mockito.doThrow(
            new WorkflowExecutionAlreadyStarted(
                io.temporal.api.common.v1.WorkflowExecution.newBuilder()
                    .setWorkflowId("t-acme/s-copytrade-v1/sig/manual:idem-1")
                    .build(),
                "CopytradeSignalWorkflow",
                null))
        .when(stub)
        .start(any());

    mvc.perform(
            post("/api/entries/manual")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(freshBody()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("duplicate_submission"));
  }

  @Test
  void manual_missingTenantHeader_is401AndStartsNothing() throws Exception {
    mvc.perform(
            post("/api/entries/manual")
                .contentType(MediaType.APPLICATION_JSON)
                .content(freshBody()))
        .andExpect(status().isUnauthorized());

    verify(stub, never()).start(any());
  }

  @Test
  void manual_qtyBelowOne_is400() throws Exception {
    mvc.perform(
            post("/api/entries/manual")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    body(OffsetDateTime.now(ZoneOffset.UTC).toString(), "2.35", 0, "copytrade-v1")))
        .andExpect(status().isBadRequest());

    verify(stub, never()).start(any());
  }

  @Test
  void manual_malformedOcc_is400AndStartsNothing() throws Exception {
    String malformed =
        "{\"occ\":\"NOTANOCC\",\"strategy_id\":\"copytrade-v1\",\"qty\":3,"
            + "\"quoted_ask\":2.35,\"quoted_at\":\""
            + OffsetDateTime.now(ZoneOffset.UTC)
            + "\",\"idempotency_key\":\"idem-1\"}";

    mvc.perform(
            post("/api/entries/manual")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(malformed))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_occ"));

    verify(stub, never()).start(any());
  }
}
