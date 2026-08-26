package com.ohmytradeagent.exec.fill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.FillSignalPayload;
import com.ohmytradeagent.exec.journal.JournaledOrder;
import com.ohmytradeagent.exec.journal.OrderIntentJournal;
import com.ohmytradeagent.exec.journal.OrderState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pins the fill-dispatch contract: journal lookup by broker_order_id → workflow ID via {@code
 * WorkflowIds.copytradeSignal} → untyped Temporal signal on {@code onFill} with a contract-owned
 * {@link FillSignalPayload}. Tests the four documented outcomes (happy path, unknown order,
 * workflow already completed, other Temporal error).
 */
class FillDispatcherImplTest {

  private OrderIntentJournal journal;
  private WorkflowClient workflowClient;
  private WorkflowStub workflowStub;
  private SimpleMeterRegistry registry;
  private FillListenerMetrics metrics;
  private FillDispatcherImpl dispatcher;

  private static final BrokerFillEvent FILL =
      new BrokerFillEvent(
          "brk-42",
          "ck-42",
          5L,
          new BigDecimal("0.84"),
          OffsetDateTime.parse("2026-05-19T17:08:11Z"),
          BrokerFillEvent.Source.WS);

  // #250: a WS partial_fill for the same order (ROW.qty() == 5). Alpaca reports filled_qty as the
  // cumulative-so-far quantity, so a partial carries filledQty (2) < order.qty() (5). It must NOT
  // terminalize the journal, but the onFill signal still fires with the partial qty.
  private static final BrokerFillEvent PARTIAL_FILL =
      new BrokerFillEvent(
          "brk-42",
          "ck-42",
          2L,
          new BigDecimal("0.83"),
          OffsetDateTime.parse("2026-05-19T17:08:09Z"),
          BrokerFillEvent.Source.WS);

  private static final JournaledOrder ROW =
      new JournaledOrder(
          "ck-42",
          "sig-42",
          "dev",
          "copytrade-v1",
          "alpaca-paper",
          "ck-42",
          "SPY   260519C00737000",
          "BUY",
          5L,
          new BigDecimal("0.84"),
          OrderState.SUBMITTED,
          "brk-42",
          OffsetDateTime.parse("2026-05-19T17:08:00Z"),
          OffsetDateTime.parse("2026-05-19T17:08:01Z"),
          OffsetDateTime.parse("2026-05-19T17:08:01Z"),
          null,
          null,
          null,
          null,
          null,
          1L);

  // Realistic STC intent_key from PositionWorkflowImpl: <position-wf-id> + ":exit:" + signalId.
  // The dispatcher must extract the position workflow ID prefix and signal IT (not the
  // CopytradeSignalWorkflow derived from the row's signalId).
  private static final String STC_INTENT_KEY =
      "t-dev/s-copytrade-v1/pos/NVDA  260516C00140000/sig-bto-1:exit:sig-stc-7";
  private static final String STC_POSITION_WF_ID =
      "t-dev/s-copytrade-v1/pos/NVDA  260516C00140000/sig-bto-1";

  private static final JournaledOrder STC_ROW =
      new JournaledOrder(
          STC_INTENT_KEY,
          "sig-stc-7",
          "dev",
          "copytrade-v1",
          "alpaca-paper",
          STC_INTENT_KEY,
          "NVDA  260516C00140000",
          "SELL",
          3L,
          new BigDecimal("2.10"),
          OrderState.SUBMITTED,
          "brk-stc-7",
          OffsetDateTime.parse("2026-05-25T14:00:00Z"),
          OffsetDateTime.parse("2026-05-25T14:00:01Z"),
          OffsetDateTime.parse("2026-05-25T14:00:01Z"),
          null,
          null,
          null,
          null,
          null,
          1L);

  private static final BrokerFillEvent STC_FILL =
      new BrokerFillEvent(
          "brk-stc-7",
          STC_INTENT_KEY,
          3L,
          new BigDecimal("2.15"),
          OffsetDateTime.parse("2026-05-25T14:00:15Z"),
          BrokerFillEvent.Source.WS);

  @BeforeEach
  void setUp() {
    journal = mock(OrderIntentJournal.class);
    workflowClient = mock(WorkflowClient.class);
    workflowStub = mock(WorkflowStub.class);
    registry = new SimpleMeterRegistry();
    metrics = new FillListenerMetrics(registry);
    dispatcher = new FillDispatcherImpl(journal, workflowClient, metrics);

    when(workflowClient.newUntypedWorkflowStub(anyString())).thenReturn(workflowStub);
  }

  // #693: until onBinary shipped, the WS delivered NOTHING and the poller found every fill, so the
  // WS×poll cooperation these two tests describe had never actually run in production despite being
  // documented on FillDispatcherImpl. Phase 1 turns it on — so assert it rather than trust the
  // javadoc.
  @Test
  void dispatch_sameFillFromWsThenPoll_terminalizesOnceAndIsHarmlessTwice() {
    when(journal.findByBrokerOrderId("brk-42")).thenReturn(Optional.of(ROW));
    // markFilled is conditional on state in (RECORDED, SUBMITTED): the WS wins the race and
    // terminalizes; the poll repeat finds the row already FILLED and returns false.
    when(journal.markFilled(eq("ck-42"), anyLong(), any(), any())).thenReturn(true, false);

    BrokerFillEvent viaPoll =
        new BrokerFillEvent(
            FILL.brokerOrderId(),
            FILL.clientOrderId(),
            FILL.filledQty(),
            FILL.avgFillPrice(),
            FILL.filledAt(),
            BrokerFillEvent.Source.POLL);

    dispatcher.dispatch(FILL);
    dispatcher.dispatch(viaPoll);

    // Both sources attempt terminalization. This asserts the DISPATCHER's half of the contract —
    // that it re-attempts rather than short-circuiting. The journal is mocked here, so the guard
    // that actually makes the repeat safe (UPDATE ... WHERE state IN (RECORDED, SUBMITTED)) is
    // proven against a real database in JooqOrderIntentJournalIT#markFilled_onTerminalState_noOp,
    // not by this test.
    verify(journal, times(2)).markFilled(eq("ck-42"), eq(5L), any(), any());
    // Both signal — onFill is idempotent by structure (single field assign, read once through
    // Workflow.await), which is what makes the at-least-once contract safe.
    verify(workflowStub, times(2)).signal(eq("onFill"), any());
  }

  @Test
  void dispatch_routesFillToWorkflowSignal() {
    when(journal.findByBrokerOrderId("brk-42")).thenReturn(Optional.of(ROW));
    // #251: stub markFilled -> true so the `if (terminalized)` info-log branch in dispatch() is
    // exercised on the happy path (Mockito otherwise returns false by default and the branch is
    // never entered here).
    when(journal.markFilled(eq("ck-42"), anyLong(), any(), any())).thenReturn(true);

    dispatcher.dispatch(FILL);

    // #244: the journal is terminalized to FILLED inside dispatch() — keyed on the resolved
    // intent_key — BEFORE the signal, so the row reaches FILLED even if the signal target has
    // already completed. markFilled is conditional on RECORDED/SUBMITTED, so the repeat is a no-op.
    ArgumentCaptor<Long> filledQty = ArgumentCaptor.forClass(Long.class);
    ArgumentCaptor<BigDecimal> avgPrice = ArgumentCaptor.forClass(BigDecimal.class);
    ArgumentCaptor<OffsetDateTime> filledAt = ArgumentCaptor.forClass(OffsetDateTime.class);
    verify(journal)
        .markFilled(eq("ck-42"), filledQty.capture(), avgPrice.capture(), filledAt.capture());
    assertThat(filledQty.getValue()).isEqualTo(5L);
    assertThat(avgPrice.getValue()).isEqualByComparingTo(new BigDecimal("0.84"));
    assertThat(filledAt.getValue()).isEqualTo(OffsetDateTime.parse("2026-05-19T17:08:11Z"));

    verify(workflowClient).newUntypedWorkflowStub("t-dev/s-copytrade-v1/sig/sig-42");

    ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object> arg = ArgumentCaptor.forClass(Object.class);
    verify(workflowStub).signal(name.capture(), arg.capture());

    assertThat(name.getValue()).isEqualTo("onFill");
    assertThat(arg.getValue())
        .isInstanceOfSatisfying(
            FillSignalPayload.class,
            p -> {
              assertThat(p.getBrokerOrderId()).isEqualTo("brk-42");
              assertThat(p.getFilledQty()).isEqualTo(5L);
              assertThat(p.getAvgFillPrice()).isEqualByComparingTo(new BigDecimal("0.84"));
              assertThat(p.getFilledAt()).isEqualTo(OffsetDateTime.parse("2026-05-19T17:08:11Z"));
            });

    // Success path is the ONLY path that bumps events_dispatched_total — pinning here so a future
    // refactor that moves the counter back out of the dispatcher (or accidentally bumps it on a
    // drop path) trips this test instead of silently mis-counting.
    assertThat(registry.counter("fill_listener.events_dispatched").count()).isEqualTo(1.0);
    assertThat(registry.counter("fill_listener.events_unknown_order").count()).isEqualTo(0.0);
    assertThat(registry.counter("fill_listener.signal_workflow_not_found").count()).isEqualTo(0.0);
    assertThat(registry.counter("fill_listener.signal_errors").count()).isEqualTo(0.0);
  }

  @Test
  void dispatch_stcIntentKey_routesToPositionWorkflow() {
    // Pins the STC routing branch: intent_key contains ":exit:" → workflow ID is the prefix
    // before the marker (= PositionWorkflow ID). Without this branch the dispatcher would
    // resolve to the short-lived STC CopytradeSignalWorkflow (already completed), the
    // PositionWorkflow would block on lastFillEvent until EOD, and PartialExitFilled would
    // never land in audit_log.
    when(journal.findByBrokerOrderId("brk-stc-7")).thenReturn(Optional.of(STC_ROW));
    when(journal.markFilled(eq(STC_INTENT_KEY), anyLong(), any(), any())).thenReturn(true);

    dispatcher.dispatch(STC_FILL);

    // #244: STC exit fills are terminalized to FILLED on the row keyed by the exit intent_key too.
    verify(journal).markFilled(eq(STC_INTENT_KEY), eq(3L), any(), any());
    verify(workflowClient).newUntypedWorkflowStub(STC_POSITION_WF_ID);
    ArgumentCaptor<Object> arg = ArgumentCaptor.forClass(Object.class);
    verify(workflowStub).signal(eq("onFill"), arg.capture());
    assertThat(arg.getValue())
        .isInstanceOfSatisfying(
            FillSignalPayload.class,
            p -> {
              assertThat(p.getBrokerOrderId()).isEqualTo("brk-stc-7");
              assertThat(p.getFilledQty()).isEqualTo(3L);
              assertThat(p.getAvgFillPrice()).isEqualByComparingTo(new BigDecimal("2.15"));
            });
    assertThat(registry.counter("fill_listener.events_dispatched").count()).isEqualTo(1.0);
  }

  // Realistic copytrade ENTRY intent_key: `<CopytradeSignalWorkflow-id>:entry`
  // (CopytradeSignalWorkflowImpl builds `Workflow.getInfo().getWorkflowId() + ":entry"`).
  private static final String COPYTRADE_ENTRY_INTENT_KEY = "t-dev/s-copytrade-v1/sig/sig-42:entry";

  private static final JournaledOrder COPYTRADE_ENTRY_ROW =
      new JournaledOrder(
          COPYTRADE_ENTRY_INTENT_KEY,
          "sig-42",
          "dev",
          "copytrade-v1",
          "alpaca-paper",
          "ck-42",
          "SPY   260519C00737000",
          "BUY",
          5L,
          new BigDecimal("0.84"),
          OrderState.SUBMITTED,
          "brk-42",
          OffsetDateTime.parse("2026-05-19T17:08:00Z"),
          OffsetDateTime.parse("2026-05-19T17:08:01Z"),
          OffsetDateTime.parse("2026-05-19T17:08:01Z"),
          null,
          null,
          null,
          null,
          null,
          1L);

  // Realistic watchlist ENTRY intent_key: `<WatchlistTriggerWorkflow leg-id>:entry`
  // (WatchlistTriggerWorkflowImpl builds `Workflow.getInfo().getWorkflowId() + ":entry"`; the leg
  // id is `t-{tenant}/s-{strategy}/wl/{et_date}/{ticker}/{C|P}`).
  private static final String WATCHLIST_LEG_WF_ID = "t-dev/s-watchlist-v1/wl/2026-07-06/QQQ/C";
  private static final String WATCHLIST_ENTRY_INTENT_KEY = WATCHLIST_LEG_WF_ID + ":entry";

  private static final JournaledOrder WATCHLIST_ENTRY_ROW =
      new JournaledOrder(
          WATCHLIST_ENTRY_INTENT_KEY,
          "wl-sig-9",
          "dev",
          "watchlist-v1",
          "alpaca-paper",
          "ck-wl-9",
          "QQQ   260710C00725000",
          "BUY",
          5L,
          new BigDecimal("1.20"),
          OrderState.SUBMITTED,
          "brk-wl-9",
          OffsetDateTime.parse("2026-07-06T18:39:00Z"),
          OffsetDateTime.parse("2026-07-06T18:39:01Z"),
          OffsetDateTime.parse("2026-07-06T18:39:01Z"),
          null,
          null,
          null,
          null,
          null,
          1L);

  private static final BrokerFillEvent WATCHLIST_ENTRY_FILL =
      new BrokerFillEvent(
          "brk-wl-9",
          "ck-wl-9",
          5L,
          new BigDecimal("1.21"),
          OffsetDateTime.parse("2026-07-06T18:39:55Z"),
          BrokerFillEvent.Source.WS);

  @Test
  void dispatch_copytradeEntryIntentKey_routesToSignalWorkflow_reconstructUnchanged() {
    // HARD REQUIREMENT 1 — copytrade non-regression. Even with the REALISTIC `:entry` intent_key
    // (`t-dev/s-copytrade-v1/sig/sig-42:entry`) the dispatcher routes the entry fill to the
    // reconstructed CopytradeSignalWorkflow id `t-dev/s-copytrade-v1/sig/sig-42` — byte-identical
    // to the prior reconstruct routing. (Prefix-strip would yield the SAME string here, proving the
    // two agree for copytrade; the dispatcher branches on `/wl/` so copytrade never leaves the
    // reconstruct path regardless.)
    when(journal.findByBrokerOrderId("brk-42")).thenReturn(Optional.of(COPYTRADE_ENTRY_ROW));
    when(journal.markFilled(eq(COPYTRADE_ENTRY_INTENT_KEY), anyLong(), any(), any()))
        .thenReturn(true);

    dispatcher.dispatch(FILL);

    verify(journal).markFilled(eq(COPYTRADE_ENTRY_INTENT_KEY), eq(5L), any(), any());
    verify(workflowClient).newUntypedWorkflowStub("t-dev/s-copytrade-v1/sig/sig-42");
    verify(workflowClient, never()).newUntypedWorkflowStub(COPYTRADE_ENTRY_INTENT_KEY);
    verify(workflowClient, never()).newUntypedWorkflowStub(WATCHLIST_LEG_WF_ID);
    verify(workflowStub).signal(eq("onFill"), any());
    assertThat(registry.counter("fill_listener.events_dispatched").count()).isEqualTo(1.0);
  }

  @Test
  void dispatch_watchlistEntryIntentKey_routesToWatchlistLeg_notReconstructedSignalWorkflow() {
    // Defect A fix: a watchlist entry fill must route to the `/wl/...` leg that placed it,
    // recovered
    // by stripping the `:entry` suffix — NOT to the reconstructed `/sig/...` id, which never
    // matches
    // a watchlist leg. Before the fix the onFill hit a non-existent `/sig/{signalId}` workflow and
    // was dropped, so the leg's `Workflow.await(ttl, () -> fillEvent != null)` never woke on a real
    // fill and the lot was orphaned until the 5-minute recon sweep adopted it.
    when(journal.findByBrokerOrderId("brk-wl-9")).thenReturn(Optional.of(WATCHLIST_ENTRY_ROW));
    when(journal.markFilled(eq(WATCHLIST_ENTRY_INTENT_KEY), anyLong(), any(), any()))
        .thenReturn(true);

    dispatcher.dispatch(WATCHLIST_ENTRY_FILL);

    verify(journal).markFilled(eq(WATCHLIST_ENTRY_INTENT_KEY), eq(5L), any(), any());
    verify(workflowClient).newUntypedWorkflowStub(WATCHLIST_LEG_WF_ID);
    // NOT the reconstructed signal-workflow id (the pre-fix mis-route).
    verify(workflowClient, never()).newUntypedWorkflowStub("t-dev/s-watchlist-v1/sig/wl-sig-9");
    ArgumentCaptor<Object> arg = ArgumentCaptor.forClass(Object.class);
    verify(workflowStub).signal(eq("onFill"), arg.capture());
    assertThat(arg.getValue())
        .isInstanceOfSatisfying(
            FillSignalPayload.class,
            p -> {
              assertThat(p.getBrokerOrderId()).isEqualTo("brk-wl-9");
              assertThat(p.getFilledQty()).isEqualTo(5L);
              assertThat(p.getAvgFillPrice()).isEqualByComparingTo(new BigDecimal("1.21"));
            });
    assertThat(registry.counter("fill_listener.events_dispatched").count()).isEqualTo(1.0);
  }

  @Test
  void dispatch_routesFillToOriginatingTenantsWorkflow_notAnotherTenants() {
    // Fleet enablement Phase 2 (per-tenant fill sockets): a fill arriving on tenant B's socket must
    // route to B's CopytradeSignalWorkflow — resolveWorkflowId is tenant-scoped
    // (WorkflowIds.copytradeSignal(order.tenantId(), ...)), so B's fill never signals A's workflow
    // id. Under the shared -live exec pod a cross-wire here would deliver B's fill to A's position.
    JournaledOrder bobRow =
        new JournaledOrder(
            "ck-bob",
            "sig-bob",
            "bob",
            "copytrade-v1",
            "alpaca-live",
            "ck-bob",
            "SPY   260519C00737000",
            "BUY",
            5L,
            new BigDecimal("0.84"),
            OrderState.SUBMITTED,
            "brk-bob",
            OffsetDateTime.parse("2026-05-19T17:08:00Z"),
            OffsetDateTime.parse("2026-05-19T17:08:01Z"),
            OffsetDateTime.parse("2026-05-19T17:08:01Z"),
            null,
            null,
            null,
            null,
            null,
            1L);
    BrokerFillEvent bobFill =
        new BrokerFillEvent(
            "brk-bob",
            "ck-bob",
            5L,
            new BigDecimal("0.84"),
            OffsetDateTime.parse("2026-05-19T17:08:11Z"),
            BrokerFillEvent.Source.WS);
    when(journal.findByBrokerOrderId("brk-bob")).thenReturn(Optional.of(bobRow));
    when(journal.markFilled(eq("ck-bob"), anyLong(), any(), any())).thenReturn(true);

    dispatcher.dispatch(bobFill);

    verify(workflowClient).newUntypedWorkflowStub("t-bob/s-copytrade-v1/sig/sig-bob");
    verify(workflowClient, never()).newUntypedWorkflowStub("t-dev/s-copytrade-v1/sig/sig-42");
    verify(workflowStub).signal(eq("onFill"), any());
  }

  @Test
  void dispatch_unknownBrokerOrder_andUnknownClientOrder_dropsEvent() {
    // #244: a truly unknown fill resolves NEITHER by broker_order_id NOR by the
    // client_order_id fallback — only then is it counted unknown and dropped.
    // #295: the fallback resolves by the bounded client_order_id, not by intent_key.
    when(journal.findByBrokerOrderId("brk-42")).thenReturn(Optional.empty());
    when(journal.findByClientOrderId("ck-42")).thenReturn(Optional.empty());

    dispatcher.dispatch(FILL);

    verify(journal, never()).markFilled(anyString(), anyLong(), any(), any());
    verify(workflowClient, never()).newUntypedWorkflowStub(anyString());
    verify(workflowStub, never()).signal(anyString(), org.mockito.ArgumentMatchers.any());
    assertThat(registry.counter("fill_listener.events_unknown_order").count()).isEqualTo(1.0);
    assertThat(registry.counter("fill_listener.events_dispatched").count()).isEqualTo(0.0);
  }

  @Test
  void dispatch_brokerOrderIdNotYetPersisted_resolvesByClientOrderId_terminalizesJournal() {
    // #244 ROOT CAUSE: the near-instant-fill race. The WS fill arrives in the ~26ms window AFTER
    // broker.placeOrder returns but BEFORE ExecActivitiesImpl.placeOrder runs
    // markSubmittedIfRecorded(intentKey, brokerOrderId) — so the row has no broker_order_id yet
    // and findByBrokerOrderId is empty. The broker echoes the client_order_id, so the dispatcher
    // resolves the row by event.clientOrderId() and terminalizes the journal to FILLED. WITHOUT
    // this the fill was logged unknown + dropped and the row stayed stuck SUBMITTED, stranding the
    // position.
    // #295: the fallback now resolves by the bounded client_order_id (findByClientOrderId), since
    // the bounded id no longer equals the intent_key.
    JournaledOrder recordedRow =
        new JournaledOrder(
            "ck-42",
            "sig-42",
            "dev",
            "copytrade-v1",
            "alpaca-paper",
            "ck-42",
            "SPY   260519C00737000",
            "BUY",
            5L,
            new BigDecimal("0.84"),
            OrderState.SUBMITTED,
            null, // broker_order_id NOT yet persisted — the race window
            OffsetDateTime.parse("2026-05-19T17:08:00Z"),
            OffsetDateTime.parse("2026-05-19T17:08:01Z"),
            OffsetDateTime.parse("2026-05-19T17:08:01Z"),
            null,
            null,
            null,
            null,
            null,
            1L);
    when(journal.findByBrokerOrderId("brk-42")).thenReturn(Optional.empty());
    when(journal.findByClientOrderId("ck-42")).thenReturn(Optional.of(recordedRow));
    when(journal.markFilled(eq("ck-42"), anyLong(), any(), any())).thenReturn(true);

    dispatcher.dispatch(FILL);

    // Journal reaches FILLED with correct fill detail — the AC #2 outcome.
    verify(journal)
        .markFilled(
            eq("ck-42"),
            eq(5L),
            eq(new BigDecimal("0.84")),
            eq(OffsetDateTime.parse("2026-05-19T17:08:11Z")));
    // The fill is NOT dropped as unknown.
    assertThat(registry.counter("fill_listener.events_unknown_order").count()).isEqualTo(0.0);
    // Still signals the originating workflow.
    verify(workflowClient).newUntypedWorkflowStub("t-dev/s-copytrade-v1/sig/sig-42");
    verify(workflowStub).signal(eq("onFill"), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void dispatch_idempotentOnRepeat_singleEffectiveFill() {
    // #244 AC #2 idempotency: the same fill arriving twice (WS then POLL) terminalizes once.
    // markFilled is conditional on state in (RECORDED, SUBMITTED), so the second call returns
    // false (no-op) and qty/price are not corrupted. The dispatcher must invoke markFilled on
    // both deliveries (the journal enforces the no-op) and must not double-count or throw.
    when(journal.findByBrokerOrderId("brk-42")).thenReturn(Optional.of(ROW));
    when(journal.markFilled(eq("ck-42"), anyLong(), any(), any()))
        .thenReturn(true) // first delivery transitions SUBMITTED → FILLED
        .thenReturn(false); // second delivery is a no-op (already terminal)

    dispatcher.dispatch(FILL);
    dispatcher.dispatch(FILL);

    // Always with the SAME fill detail — a repeat cannot rewrite qty/price.
    verify(journal, times(2))
        .markFilled(
            eq("ck-42"),
            eq(5L),
            eq(new BigDecimal("0.84")),
            eq(OffsetDateTime.parse("2026-05-19T17:08:11Z")));
  }

  // ---------- #819 Phase B: entry straggler reroute ----------

  /**
   * #819: an ENTRY fill whose parent signal workflow already completed (the straggler slice that
   * used to be dropped) is rerouted ONCE to the owning PositionWorkflow, whose id is derived from
   * the journal row through the SHARED contract helper — the exact id is asserted so a format drift
   * between exec and orchestrator cannot pass. Feeds #801's bookEntryGrowth.
   */
  @Test
  void dispatch_entryStragglerWorkflowNotFound_reroutesToPositionWorkflow() {
    when(journal.findByBrokerOrderId("brk-42")).thenReturn(Optional.of(COPYTRADE_ENTRY_ROW));
    when(journal.markFilled(eq(COPYTRADE_ENTRY_INTENT_KEY), anyLong(), any(), any()))
        .thenReturn(true);
    String parentId = "t-dev/s-copytrade-v1/sig/sig-42";
    String positionId =
        com.ohmytradeagent.contract.identity.WorkflowIds.position(
            "dev", "copytrade-v1", "SPY   260519C00737000", "sig-42");
    WorkflowStub parentStub = mock(WorkflowStub.class);
    WorkflowStub positionStub = mock(WorkflowStub.class);
    when(workflowClient.newUntypedWorkflowStub(parentId)).thenReturn(parentStub);
    when(workflowClient.newUntypedWorkflowStub(positionId)).thenReturn(positionStub);
    WorkflowExecution parentExec = WorkflowExecution.newBuilder().setWorkflowId(parentId).build();
    doThrow(new WorkflowNotFoundException(parentExec, "CopytradeSignalWorkflow", null))
        .when(parentStub)
        .signal(eq("onFill"), any());

    dispatcher.dispatch(FILL);

    ArgumentCaptor<Object> arg = ArgumentCaptor.forClass(Object.class);
    verify(positionStub).signal(eq("onFill"), arg.capture());
    assertThat(arg.getValue())
        .isInstanceOfSatisfying(
            FillSignalPayload.class, p -> assertThat(p.getFilledQty()).isEqualTo(5L));
    // Falsify finding 3: the plan's own criterion — the first live straggler must be observable.
    assertThat(registry.counter("fill_listener.entry_straggler_reroutes").count()).isEqualTo(1.0);
    assertThat(registry.counter("fill_listener.events_dispatched").count()).isEqualTo(1.0);
    // The event was DELIVERED, not dropped: the drop counter must not fire.
    assertThat(registry.counter("fill_listener.signal_workflow_not_found").count()).isEqualTo(0.0);
  }

  /**
   * #819 goal-review finding 7: a RE-PEGGED replacement's key (`<wf-id>:entry:repeg-1`) must
   * reroute too — same owning position; endsWith(":entry") would have silently excluded it.
   */
  @Test
  void dispatch_repegReplacementStraggler_reroutesToSamePosition() {
    String repegKey = COPYTRADE_ENTRY_INTENT_KEY + ":repeg-1";
    JournaledOrder repegRow =
        new JournaledOrder(
            repegKey,
            "sig-42",
            "dev",
            "copytrade-v1",
            "alpaca-paper",
            "ck-42r",
            "SPY   260519C00737000",
            "BUY",
            5L,
            new BigDecimal("0.84"),
            OrderState.SUBMITTED,
            "brk-42r",
            OffsetDateTime.parse("2026-05-19T17:08:00Z"),
            OffsetDateTime.parse("2026-05-19T17:08:01Z"),
            OffsetDateTime.parse("2026-05-19T17:08:01Z"),
            null,
            null,
            null,
            null,
            null,
            1L);
    when(journal.findByBrokerOrderId("brk-42r")).thenReturn(Optional.of(repegRow));
    when(journal.markFilled(eq(repegKey), anyLong(), any(), any())).thenReturn(true);
    String positionId =
        com.ohmytradeagent.contract.identity.WorkflowIds.position(
            "dev", "copytrade-v1", "SPY   260519C00737000", "sig-42");
    WorkflowStub primaryStub = mock(WorkflowStub.class);
    WorkflowStub positionStub = mock(WorkflowStub.class);
    // resolveWorkflowId reconstructs the copytrade signal wf id for this row.
    when(workflowClient.newUntypedWorkflowStub("t-dev/s-copytrade-v1/sig/sig-42"))
        .thenReturn(primaryStub);
    when(workflowClient.newUntypedWorkflowStub(positionId)).thenReturn(positionStub);
    doThrow(
            new WorkflowNotFoundException(
                WorkflowExecution.newBuilder()
                    .setWorkflowId("t-dev/s-copytrade-v1/sig/sig-42")
                    .build(),
                "CopytradeSignalWorkflow",
                null))
        .when(primaryStub)
        .signal(eq("onFill"), any());

    dispatcher.dispatch(
        new BrokerFillEvent(
            "brk-42r",
            "ck-42r",
            5L,
            new BigDecimal("0.84"),
            OffsetDateTime.parse("2026-05-19T17:08:11Z"),
            BrokerFillEvent.Source.WS));

    verify(positionStub).signal(eq("onFill"), any());
  }

  /**
   * #819: an EXIT fill's WorkflowNotFound keeps today's benign-drop behavior — exit fills already
   * route to the position workflow by intent-key prefix, so a reroute would signal the SAME gone
   * workflow (or worse, a wrongly-derived one).
   */
  @Test
  void dispatch_exitIntentWorkflowNotFound_neverReroutes() {
    JournaledOrder exitRow =
        new JournaledOrder(
            STC_POSITION_WF_ID + ":exit:sig-77",
            "sig-77",
            "dev",
            "copytrade-v1",
            "alpaca-paper",
            "ck-77",
            "SPY   260519C00737000",
            "SELL",
            5L,
            new BigDecimal("0.84"),
            OrderState.SUBMITTED,
            "brk-77",
            OffsetDateTime.parse("2026-05-19T17:08:00Z"),
            OffsetDateTime.parse("2026-05-19T17:08:01Z"),
            OffsetDateTime.parse("2026-05-19T17:08:01Z"),
            null,
            null,
            null,
            null,
            null,
            1L);
    when(journal.findByBrokerOrderId("brk-77")).thenReturn(Optional.of(exitRow));
    when(journal.markFilled(anyString(), anyLong(), any(), any())).thenReturn(true);
    WorkflowStub exitStub = mock(WorkflowStub.class);
    when(workflowClient.newUntypedWorkflowStub(STC_POSITION_WF_ID)).thenReturn(exitStub);
    WorkflowExecution exec =
        WorkflowExecution.newBuilder().setWorkflowId(STC_POSITION_WF_ID).build();
    doThrow(new WorkflowNotFoundException(exec, "PositionWorkflow", null))
        .when(exitStub)
        .signal(eq("onFill"), any());

    dispatcher.dispatch(
        new BrokerFillEvent(
            "brk-77",
            "ck-77",
            5L,
            new BigDecimal("0.84"),
            OffsetDateTime.parse("2026-05-19T17:08:11Z"),
            BrokerFillEvent.Source.WS));

    // Only the primary target was ever resolved — no reroute stub lookup happened.
    verify(workflowClient, times(1)).newUntypedWorkflowStub(anyString());
  }

  /**
   * Falsify finding 1: a non-NOT_FOUND Temporal failure MID-REROUTE must propagate AND be counted
   * via signal_errors, exactly like the primary signal path — the uncounted-escape regression this
   * pins was fixed once and had no test.
   */
  @Test
  void dispatch_rerouteThrowsNonNotFound_propagatesAndCounts() {
    when(journal.findByBrokerOrderId("brk-42")).thenReturn(Optional.of(COPYTRADE_ENTRY_ROW));
    when(journal.markFilled(eq(COPYTRADE_ENTRY_INTENT_KEY), anyLong(), any(), any()))
        .thenReturn(true);
    String parentId = "t-dev/s-copytrade-v1/sig/sig-42";
    String positionId =
        com.ohmytradeagent.contract.identity.WorkflowIds.position(
            "dev", "copytrade-v1", "SPY   260519C00737000", "sig-42");
    WorkflowStub parentStub = mock(WorkflowStub.class);
    WorkflowStub positionStub = mock(WorkflowStub.class);
    when(workflowClient.newUntypedWorkflowStub(parentId)).thenReturn(parentStub);
    when(workflowClient.newUntypedWorkflowStub(positionId)).thenReturn(positionStub);
    doThrow(
            new WorkflowNotFoundException(
                WorkflowExecution.newBuilder().setWorkflowId(parentId).build(),
                "CopytradeSignalWorkflow",
                null))
        .when(parentStub)
        .signal(eq("onFill"), any());
    doThrow(new IllegalStateException("temporal unavailable"))
        .when(positionStub)
        .signal(eq("onFill"), any());

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> dispatcher.dispatch(FILL))
        .isInstanceOf(IllegalStateException.class);
    assertThat(registry.counter("fill_listener.signal_errors").count()).isEqualTo(1.0);
  }

  /**
   * Falsify finding 4: pins the exit-marker guard's PRECEDENCE — a pathological key containing BOTH
   * markers must be treated as an exit (no reroute). No real key has this shape (traced), but
   * without the pin the guard reads as dead code and invites deletion.
   */
  @Test
  void dispatch_keyWithBothMarkers_treatedAsExit_neverReroutes() {
    String bothKey = STC_POSITION_WF_ID + ":exit:sig-x:entry";
    JournaledOrder bothRow =
        new JournaledOrder(
            bothKey,
            "sig-x",
            "dev",
            "copytrade-v1",
            "alpaca-paper",
            "ck-x",
            "SPY   260519C00737000",
            "SELL",
            5L,
            new BigDecimal("0.84"),
            OrderState.SUBMITTED,
            "brk-x",
            OffsetDateTime.parse("2026-05-19T17:08:00Z"),
            OffsetDateTime.parse("2026-05-19T17:08:01Z"),
            OffsetDateTime.parse("2026-05-19T17:08:01Z"),
            null,
            null,
            null,
            null,
            null,
            1L);
    when(journal.findByBrokerOrderId("brk-x")).thenReturn(Optional.of(bothRow));
    when(journal.markFilled(anyString(), anyLong(), any(), any())).thenReturn(true);
    WorkflowStub primaryStub = mock(WorkflowStub.class);
    when(workflowClient.newUntypedWorkflowStub(STC_POSITION_WF_ID)).thenReturn(primaryStub);
    doThrow(
            new WorkflowNotFoundException(
                WorkflowExecution.newBuilder().setWorkflowId(STC_POSITION_WF_ID).build(),
                "PositionWorkflow",
                null))
        .when(primaryStub)
        .signal(eq("onFill"), any());

    dispatcher.dispatch(
        new BrokerFillEvent(
            "brk-x",
            "ck-x",
            5L,
            new BigDecimal("0.84"),
            OffsetDateTime.parse("2026-05-19T17:08:11Z"),
            BrokerFillEvent.Source.WS));

    verify(workflowClient, times(1)).newUntypedWorkflowStub(anyString());
  }

  /** #819: reroute target ALSO gone — falls through to today's benign log, nothing propagates. */
  @Test
  void dispatch_entryStragglerDoubleNotFound_staysBenign() {
    when(journal.findByBrokerOrderId("brk-42")).thenReturn(Optional.of(COPYTRADE_ENTRY_ROW));
    when(journal.markFilled(eq(COPYTRADE_ENTRY_INTENT_KEY), anyLong(), any(), any()))
        .thenReturn(true);
    String parentId = "t-dev/s-copytrade-v1/sig/sig-42";
    String positionId =
        com.ohmytradeagent.contract.identity.WorkflowIds.position(
            "dev", "copytrade-v1", "SPY   260519C00737000", "sig-42");
    WorkflowStub parentStub = mock(WorkflowStub.class);
    WorkflowStub positionStub = mock(WorkflowStub.class);
    when(workflowClient.newUntypedWorkflowStub(parentId)).thenReturn(parentStub);
    when(workflowClient.newUntypedWorkflowStub(positionId)).thenReturn(positionStub);
    doThrow(
            new WorkflowNotFoundException(
                WorkflowExecution.newBuilder().setWorkflowId(parentId).build(),
                "CopytradeSignalWorkflow",
                null))
        .when(parentStub)
        .signal(eq("onFill"), any());
    doThrow(
            new WorkflowNotFoundException(
                WorkflowExecution.newBuilder().setWorkflowId(positionId).build(),
                "PositionWorkflow",
                null))
        .when(positionStub)
        .signal(eq("onFill"), any());

    dispatcher.dispatch(FILL); // must not throw

    verify(positionStub).signal(eq("onFill"), any());
  }

  @Test
  void dispatch_workflowAlreadyCompleted_swallowsException_butJournalStillTerminalized() {
    // #244: the strand fix. Even when the onFill signal target has already completed
    // (WorkflowNotFoundException, swallowed as benign), the journal MUST still be terminalized to
    // FILLED — markFilled runs before the signal — so the row never sticks at SUBMITTED. Previously
    // the dispatcher only signalled and swallowed the NOT_FOUND, leaving the row stranded.
    when(journal.findByBrokerOrderId("brk-42")).thenReturn(Optional.of(ROW));
    when(journal.markFilled(eq("ck-42"), anyLong(), any(), any())).thenReturn(true);
    WorkflowExecution exec =
        WorkflowExecution.newBuilder().setWorkflowId("t-dev/s-copytrade-v1/sig/sig-42").build();
    doThrow(new WorkflowNotFoundException(exec, "CopytradeSignalWorkflow", null))
        .when(workflowStub)
        .signal(eq("onFill"), org.mockito.ArgumentMatchers.any());

    dispatcher.dispatch(FILL); // must not throw

    verify(journal)
        .markFilled(
            eq("ck-42"),
            eq(5L),
            eq(new BigDecimal("0.84")),
            eq(OffsetDateTime.parse("2026-05-19T17:08:11Z")));
    assertThat(registry.counter("fill_listener.signal_workflow_not_found").count()).isEqualTo(1.0);
    assertThat(registry.counter("fill_listener.signal_errors").count()).isEqualTo(0.0);
    assertThat(registry.counter("fill_listener.events_dispatched").count()).isEqualTo(0.0);
  }

  @Test
  void dispatch_otherTemporalException_propagates_afterJournalTerminalized() {
    // #244: the journal is terminalized BEFORE the signal, so a hard (non-NOT_FOUND) Temporal
    // failure still leaves the row FILLED and propagates for retry — the position is never
    // stranded even if the signal genuinely fails.
    when(journal.findByBrokerOrderId("brk-42")).thenReturn(Optional.of(ROW));
    when(journal.markFilled(eq("ck-42"), anyLong(), any(), any())).thenReturn(true);
    doThrow(new RuntimeException("temporal boom"))
        .when(workflowStub)
        .signal(eq("onFill"), org.mockito.ArgumentMatchers.any());

    assertThatThrownBy(() -> dispatcher.dispatch(FILL)).hasMessage("temporal boom");

    verify(journal).markFilled(eq("ck-42"), anyLong(), any(), any());
    assertThat(registry.counter("fill_listener.signal_errors").count()).isEqualTo(1.0);
    assertThat(registry.counter("fill_listener.signal_workflow_not_found").count()).isEqualTo(0.0);
    assertThat(registry.counter("fill_listener.events_dispatched").count()).isEqualTo(0.0);
  }

  @Test
  void dispatch_markFilledThrows_propagates() {
    // #251: markFilled runs BEFORE the onFill signal (terminalize-then-signal, #244). If
    // markFilled throws, the exception must propagate and the signal must NOT be sent — the row is
    // not prematurely treated as FILLED-then-signalled when terminalization itself failed.
    when(journal.findByBrokerOrderId("brk-42")).thenReturn(Optional.of(ROW));
    doThrow(new RuntimeException("journal boom"))
        .when(journal)
        .markFilled(eq("ck-42"), anyLong(), any(), any());

    assertThatThrownBy(() -> dispatcher.dispatch(FILL)).hasMessage("journal boom");

    // The signal target is never resolved or signalled — markFilled threw first.
    verify(workflowClient, never()).newUntypedWorkflowStub(anyString());
    verify(workflowStub, never()).signal(anyString(), org.mockito.ArgumentMatchers.any());
    assertThat(registry.counter("fill_listener.events_dispatched").count()).isEqualTo(0.0);
  }

  @Test
  void dispatch_wsPartialFill_doesNotTerminalizeJournal_butStillSignals() {
    // #250 AC #1: a WS partial_fill (filledQty 2 < order.qty 5) must NOT terminalize the journal
    // to FILLED — terminalizing with the partial qty would lose remaining-qty accounting and lock
    // the row at a smaller filled_qty. The onFill signal IS still sent with the partial qty so the
    // pre-existing partial-fill signalling behaviour is preserved.
    when(journal.findByBrokerOrderId("brk-42")).thenReturn(Optional.of(ROW));

    dispatcher.dispatch(PARTIAL_FILL);

    verify(journal, never()).markFilled(anyString(), anyLong(), any(), any());

    verify(workflowClient).newUntypedWorkflowStub("t-dev/s-copytrade-v1/sig/sig-42");
    ArgumentCaptor<Object> arg = ArgumentCaptor.forClass(Object.class);
    verify(workflowStub).signal(eq("onFill"), arg.capture());
    assertThat(arg.getValue())
        .isInstanceOfSatisfying(
            FillSignalPayload.class,
            p -> {
              assertThat(p.getBrokerOrderId()).isEqualTo("brk-42");
              assertThat(p.getFilledQty()).isEqualTo(2L);
              assertThat(p.getAvgFillPrice()).isEqualByComparingTo(new BigDecimal("0.83"));
              assertThat(p.getFilledAt()).isEqualTo(OffsetDateTime.parse("2026-05-19T17:08:09Z"));
            });
    assertThat(registry.counter("fill_listener.events_dispatched").count()).isEqualTo(1.0);
  }

  @Test
  void dispatch_wsCompleteFill_terminalizesJournalWithFullQty() {
    // #250 AC #2 / derived: a complete WS fill (filledQty 5 >= order.qty 5) terminalizes the
    // journal exactly as before — with the event's full qty/price/time.
    when(journal.findByBrokerOrderId("brk-42")).thenReturn(Optional.of(ROW));
    when(journal.markFilled(eq("ck-42"), anyLong(), any(), any())).thenReturn(true);

    dispatcher.dispatch(FILL);

    verify(journal)
        .markFilled(
            eq("ck-42"),
            eq(5L),
            eq(new BigDecimal("0.84")),
            eq(OffsetDateTime.parse("2026-05-19T17:08:11Z")));
  }

  @Test
  void dispatch_wsPartialThenFull_endsFilledWithFullQty() {
    // #250 AC #2 regression: a WS partial_fill (filledQty 2) followed by the terminal full fill
    // (filledQty 5) ends with the journal FILLED at the FULL qty — never at the partial qty. The
    // partial leaves the row untouched (no markFilled); only the full fill terminalizes.
    when(journal.findByBrokerOrderId("brk-42")).thenReturn(Optional.of(ROW));
    when(journal.markFilled(eq("ck-42"), anyLong(), any(), any())).thenReturn(true);

    dispatcher.dispatch(PARTIAL_FILL);
    dispatcher.dispatch(FILL);

    // The partial NEVER calls markFilled; the full fill terminalizes with the full qty exactly
    // once.
    verify(journal, times(1))
        .markFilled(
            eq("ck-42"),
            eq(5L),
            eq(new BigDecimal("0.84")),
            eq(OffsetDateTime.parse("2026-05-19T17:08:11Z")));
    verify(journal, never()).markFilled(eq("ck-42"), eq(2L), any(), any());
  }

  @Test
  void dispatch_pollFullFill_stillTerminalizes() {
    // #250 poll-backstop regression guard: the POLL path only ever delivers a full-qty fill
    // (AlpacaPaperBroker.mapStatus maps partially_filled -> OPEN, only filled -> FILLED), so a
    // Source.POLL event with the full qty must still terminalize — the complete-fill guard does
    // not break the immune POLL path.
    BrokerFillEvent pollFill =
        new BrokerFillEvent(
            "brk-42",
            "ck-42",
            5L,
            new BigDecimal("0.84"),
            OffsetDateTime.parse("2026-05-19T17:08:11Z"),
            BrokerFillEvent.Source.POLL);
    when(journal.findByBrokerOrderId("brk-42")).thenReturn(Optional.of(ROW));
    when(journal.markFilled(eq("ck-42"), anyLong(), any(), any())).thenReturn(true);

    dispatcher.dispatch(pollFill);

    verify(journal)
        .markFilled(
            eq("ck-42"),
            eq(5L),
            eq(new BigDecimal("0.84")),
            eq(OffsetDateTime.parse("2026-05-19T17:08:11Z")));
  }
}
