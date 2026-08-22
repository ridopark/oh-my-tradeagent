package com.ohmytradeagent.orchestrator.alert.tradecontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.alert.floorbreach.MarketDataOptionQuoteClient;
import com.ohmytradeagent.orchestrator.alert.floorbreach.MarketDataOptionQuoteClient.OptionGreeksSnapshot;
import com.ohmytradeagent.orchestrator.alert.floorbreach.MarketDataOptionQuoteClient.OptionQuote;
import com.ohmytradeagent.orchestrator.alert.tradecontext.TradeContextRepository.OpenRow;
import com.ohmytradeagent.orchestrator.platform.TenantStrategy;
import com.ohmytradeagent.orchestrator.workflows.PositionState;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Issue #783: recorder behavior over mocked seams — one entry snapshot per (signal, tenant) with
 * greeks/spot/DTE/moneyness, per-poll ratchets, exit appends for vanished workflows, and the two
 * non-negotiables: fail-soft (a throwing repository never propagates) and no re-snapshot of an
 * already-known position.
 */
class TradeContextRecorderTest {

  private static final TenantStrategy TS = new TenantStrategy("acme", "copytrade-v1");
  private static final String OCC = "NVDA  270115C00140000";
  private static final String SIGNAL = "chat-messages-77-15:0";
  private static final String WF = WorkflowIds.position("acme", "copytrade-v1", OCC, SIGNAL);

  private TradeContextRepository repo;
  private MarketDataOptionQuoteClient quoteClient;
  private TradeContextRecorder recorder;

  @BeforeEach
  void setUp() {
    repo = mock(TradeContextRepository.class);
    quoteClient = mock(MarketDataOptionQuoteClient.class);
    when(repo.enabled()).thenReturn(true);
    // No orchestrator DSLContext in unit tests: capital_weight resolves to null, fail-soft.
    recorder = new TradeContextRecorder(repo, quoteClient, null);
  }

  private static PositionState state() {
    return new PositionState(
        OCC, 3, new BigDecimal("2.00"), OffsetDateTime.parse("2026-08-21T13:40:00Z"), false);
  }

  private static OptionQuote quote(String bid, String ask) {
    BigDecimal b = new BigDecimal(bid);
    BigDecimal a = new BigDecimal(ask);
    return new OptionQuote(b, b.add(a).divide(BigDecimal.TWO), a);
  }

  @Test
  void firstObservation_writesOneEntryRowWithGreeksSpotDteAndMoneyness() {
    when(quoteClient.optionGreeks(OCC))
        .thenReturn(
            new OptionGreeksSnapshot(
                new BigDecimal("0.54"),
                new BigDecimal("0.61"),
                new BigDecimal("0.04"),
                new BigDecimal("-0.11"),
                new BigDecimal("0.09")));
    when(quoteClient.underlyingSpot("NVDA")).thenReturn(new BigDecimal("147.00"));

    recorder.observe(TS, WF, state(), quote("1.90", "2.10"));

    ArgumentCaptor<TradeContextEntry> captor = ArgumentCaptor.forClass(TradeContextEntry.class);
    verify(repo, times(1)).upsertEntry(captor.capture());
    TradeContextEntry e = captor.getValue();
    assertThat(e.signalId()).isEqualTo(SIGNAL);
    assertThat(e.tenantId()).isEqualTo("acme");
    assertThat(e.strategyId()).isEqualTo("copytrade-v1");
    assertThat(e.workflowId()).isEqualTo(WF);
    assertThat(e.contractSymbol()).isEqualTo(OCC);
    assertThat(e.entryPremium()).isEqualByComparingTo("2.00");
    assertThat(e.entryQty()).isEqualTo(3);
    assertThat(e.entryBid()).isEqualByComparingTo("1.90");
    assertThat(e.entryAsk()).isEqualByComparingTo("2.10");
    assertThat(e.entrySpread()).isEqualByComparingTo("0.20");
    assertThat(e.iv()).isEqualByComparingTo("0.54");
    assertThat(e.delta()).isEqualByComparingTo("0.61");
    assertThat(e.vega()).isEqualByComparingTo("0.09");
    assertThat(e.underlyingSpot()).isEqualByComparingTo("147.00");
    // 140 strike, spot 147 -> ITM call, moneyness above 1.
    assertThat(e.moneyness()).isEqualByComparingTo("1.05");
    assertThat(e.dte()).isPositive();
    assertThat(e.capitalWeight()).isNull(); // no orchestrator DB in this test
    assertThat(e.quoteState()).isEqualTo("ok");

    // The poll's bid also ratchets MFE/MAE on the very first observation.
    verify(repo).ratchet(SIGNAL, "acme", WF, new BigDecimal("1.90"));
  }

  @Test
  void steadyState_secondObservationRatchetsWithoutASecondEntrySnapshot() {
    recorder.observe(TS, WF, state(), quote("1.90", "2.10"));
    recorder.observe(TS, WF, state(), quote("2.30", "2.50"));

    verify(repo, times(1)).upsertEntry(any());
    // The extra market-data snapshot calls happen once, on the entry path only.
    verify(quoteClient, times(1)).optionGreeks(OCC);
    verify(quoteClient, times(1)).underlyingSpot("NVDA");
    verify(repo).ratchet(SIGNAL, "acme", WF, new BigDecimal("1.90"));
    verify(repo).ratchet(SIGNAL, "acme", WF, new BigDecimal("2.30"));
  }

  @Test
  void missingQuote_writesEntryWithNullsAndUnknownMarker_andSkipsTheRatchet() {
    recorder.observe(TS, WF, state(), null);

    ArgumentCaptor<TradeContextEntry> captor = ArgumentCaptor.forClass(TradeContextEntry.class);
    verify(repo).upsertEntry(captor.capture());
    assertThat(captor.getValue().entryBid()).isNull();
    assertThat(captor.getValue().entrySpread()).isNull();
    assertThat(captor.getValue().quoteState()).isEqualTo("unknown");
    verify(repo, never()).ratchet(anyString(), anyString(), anyString(), any());
  }

  @Test
  void workflowIdWithoutAnEntrySignalId_isSkippedEntirely() {
    recorder.observe(TS, "t-acme/s-copytrade-v1/recon/alpaca-live/run-1", state(), quote("1", "2"));

    verify(repo, never()).upsertEntry(any());
    verify(repo, never()).ratchet(anyString(), anyString(), anyString(), any());
    verifyNoInteractions(quoteClient);
  }

  @Test
  void throwingRepository_neverPropagates_fromObserve() {
    doThrow(new IllegalStateException("dashboard db down")).when(repo).upsertEntry(any());

    assertThatCode(() -> recorder.observe(TS, WF, state(), quote("1.90", "2.10")))
        .doesNotThrowAnyException();
  }

  @Test
  void closeVanished_closesOnlyRowsWhoseWorkflowDisappeared_withAFreshExitSnapshot() {
    String goneWf = WorkflowIds.position("acme", "copytrade-v1", OCC, "sig-gone");
    when(repo.openRows())
        .thenReturn(
            List.of(
                new OpenRow("sig-live", "acme", WF, OCC),
                new OpenRow("sig-gone", "acme", goneWf, OCC)));
    when(quoteClient.optionQuote(OCC)).thenReturn(quote("0.90", "1.10"));
    when(quoteClient.optionGreeks(OCC))
        .thenReturn(new OptionGreeksSnapshot(new BigDecimal("0.71"), null, null, null, null));

    recorder.closeVanished(Set.of(WF));

    verify(repo, times(1))
        .close("sig-gone", "acme", new BigDecimal("0.90"), new BigDecimal("0.71"));
    verify(repo, never()).close(org.mockito.ArgumentMatchers.eq("sig-live"), any(), any(), any());
  }

  @Test
  void closeVanished_missingExitQuote_closesWithNulls() {
    String goneWf = WorkflowIds.position("acme", "copytrade-v1", OCC, "sig-gone");
    when(repo.openRows()).thenReturn(List.of(new OpenRow("sig-gone", "acme", goneWf, OCC)));
    when(quoteClient.optionQuote(OCC)).thenReturn(null);
    when(quoteClient.optionGreeks(OCC)).thenReturn(null);

    recorder.closeVanished(Set.of());

    verify(repo).close("sig-gone", "acme", null, null);
  }

  @Test
  void throwingRepository_neverPropagates_fromCloseVanished() {
    when(repo.openRows()).thenThrow(new IllegalStateException("dashboard db down"));

    assertThatCode(() -> recorder.closeVanished(Set.of())).doesNotThrowAnyException();
  }

  @Test
  void disabledRepository_meansNoWorkAtAll() {
    when(repo.enabled()).thenReturn(false);

    recorder.observe(TS, WF, state(), quote("1.90", "2.10"));
    recorder.closeVanished(Set.of());

    verify(repo, never()).upsertEntry(any());
    verify(repo, never()).ratchet(anyString(), anyString(), anyString(), any());
    verify(repo, never()).openRows();
    verifyNoInteractions(quoteClient);
  }

  @Test
  void aClosedKeyIsForgotten_soAReAdoptedPositionResumesRecording() {
    // Entry, then the position vanishes (Visibility lag / manual close), then recon adoption
    // restarts a workflow for the SAME signal id. The recorder must be willing to upsert again
    // (the SQL's ON CONFLICT keeps the original snapshot; what matters is the ratchet resumes
    // against the same row and re-opens it).
    lenient().when(quoteClient.optionQuote(OCC)).thenReturn(quote("1.00", "1.20"));
    recorder.observe(TS, WF, state(), quote("1.90", "2.10"));
    when(repo.openRows()).thenReturn(List.of(new OpenRow(SIGNAL, "acme", WF, OCC)));
    recorder.closeVanished(Set.of()); // WF vanished -> row closed, key forgotten

    recorder.observe(TS, WF, state(), quote("1.50", "1.70"));

    verify(repo, times(2)).upsertEntry(any());
    verify(repo).ratchet(SIGNAL, "acme", WF, new BigDecimal("1.50"));
  }
}
