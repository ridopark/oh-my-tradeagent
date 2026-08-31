package com.ohmytradeagent.exec.fill;

import com.ohmytradeagent.contract.FillSignalPayload;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.exec.journal.JournaledOrder;
import com.ohmytradeagent.exec.journal.OrderIntentJournal;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowStub;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves a broker-reported {@link BrokerFillEvent} back to the originating workflow and signals
 * {@code onFill}. The target workflow depends on whether the fill is for an entry (BTO) or an exit
 * (STC):
 *
 * <ul>
 *   <li><b>BTO fills</b> (intent_key does not contain {@code :exit:}): route to the entry workflow
 *       that placed the order. Every entry intent_key is {@code <owning-workflow-id>:entry}. For a
 *       <b>watchlist</b> leg (prefix carries {@code /wl/}) the owning id ({@code
 *       t-{tenant}/s-{strategy}/wl/{et_date}/{ticker}/{C|P}}) can NOT be reconstructed from the
 *       row's {@code signalId}, so the fill is routed by stripping the {@code :entry} suffix — the
 *       same prefix-extraction used for exits. Without it the watchlist entry {@code onFill} lands
 *       on a non-existent {@code /sig/...} id ({@link WorkflowNotFoundException}, dropped), so the
 *       leg's {@code Workflow.await(ttl, () -> fillEvent != null)} never wakes on a real broker
 *       fill and the lot stays unmanaged until the 5-minute recon sweep adopts it. For
 *       <b>copytrade</b> (and any other entry) the dispatcher KEEPS its existing reconstruct
 *       routing via {@link WorkflowIds#copytradeSignal(String, String, String)} — byte-identical to
 *       the prior behaviour — so this change's blast radius is exactly the watchlist path.
 *   <li><b>STC fills</b> (intent_key contains {@code :exit:}): route to the {@code
 *       PositionWorkflow} that placed the exit order. The intent_key encodes the position workflow
 *       ID as its prefix — extract everything before the first {@code :exit:} marker. Without this
 *       branch, the dispatcher would route STC fills to the short-lived signal workflow (already
 *       completed), the PositionWorkflow's {@code Workflow.await} would block until EOD flatten,
 *       and audit timelines would never show {@code PartialExitFilled}.
 * </ul>
 *
 * <p>Both targets accept the same {@link FillSignalPayload} JSON shape — Temporal's Jackson data
 * converter rehydrates the camelCase JSON into the receiver-side bean by {@code @JsonProperty}.
 * Using untyped workflow stubs avoids dragging orchestrator-side interfaces across the module
 * boundary.
 *
 * <p>Registered as the active {@link FillDispatcher} bean; {@link NoopFillDispatcher} falls back
 * via {@code @ConditionalOnMissingBean} only in contexts where this bean is excluded
 * (transport-only smoke tests, etc).
 *
 * <p>At-least-once contract: both target workflows' {@code onFill} handlers are idempotent by
 * structure — each assigns a single private field and the main path reads it once through {@code
 * Workflow.await(..., () -> fillEvent != null ...)}. A signal arriving after the workflow has
 * completed raises {@link WorkflowNotFoundException}, which this dispatcher swallows. The WS
 * listener and polling fallback may therefore both fire for the same fill without coordination. See
 * {@code docs/ops/fill-listener.md} for the cooperation model.
 */
@Component
public class FillDispatcherImpl implements FillDispatcher {

  private static final Logger log = LoggerFactory.getLogger(FillDispatcherImpl.class);
  private static final String SIGNAL_NAME = "onFill";

  /**
   * Marker substring placed by {@code PositionWorkflowImpl} between the position workflow ID
   * (prefix) and the STC signal ID (suffix) when constructing an exit-order intent key: {@code
   * <position-wf-id>:exit:<stc-signal-id>}. Anything containing this marker is an exit fill; the
   * dispatcher routes such fills to the PositionWorkflow instead of the CopytradeSignalWorkflow.
   */
  private static final String EXIT_INTENT_KEY_MARKER = ":exit:";

  /**
   * Suffix appended by every entry-order placer when constructing an entry intent key: {@code
   * <owning-workflow-id>:entry} ({@code CopytradeSignalWorkflowImpl}, {@code
   * WatchlistTriggerWorkflowImpl}). Stripping it recovers the owning workflow id. Used only for the
   * watchlist routing branch; copytrade keeps its reconstruct path (see {@link
   * #resolveWorkflowId}).
   */
  private static final String ENTRY_INTENT_KEY_MARKER = ":entry";

  /**
   * Marker segment identifying a {@code WatchlistTriggerWorkflow} leg id ({@code
   * .../wl/{et_date}/{ticker}/{C|P}}). Only entry fills whose intent-key prefix carries this
   * segment take the new prefix-strip routing; every other entry fill stays on the copytrade
   * reconstruct path, so this change's blast radius is exactly the watchlist path.
   */
  private static final String WATCHLIST_ID_SEGMENT = "/wl/";

  private final OrderIntentJournal journal;
  private final WorkflowClient workflowClient;
  private final FillListenerMetrics metrics;

  public FillDispatcherImpl(
      OrderIntentJournal journal, WorkflowClient workflowClient, FillListenerMetrics metrics) {
    this.journal = journal;
    this.workflowClient = workflowClient;
    this.metrics = metrics;
  }

  @Override
  public void dispatch(BrokerFillEvent event) {
    // #244: resolve the journal row by broker_order_id first, then fall back to the
    // client_order_id. The fallback closes the ~26ms submit/fill race: a WS fill can arrive AFTER
    // broker.placeOrder returns but BEFORE ExecActivitiesImpl.placeOrder runs
    // markSubmittedIfRecorded(intentKey, brokerOrderId), so the row carries no broker_order_id yet
    // and findByBrokerOrderId is empty. Without the fallback the fill was logged unknown + dropped,
    // leaving the row stuck SUBMITTED and stranding the position. Only a fill that resolves by
    // NEITHER key is a genuine unknown.
    //
    // #295: the broker echoes the bounded client_order_id (ClientOrderId.forIntent(intent_key)),
    // which is NO LONGER equal to the intent_key, so the fallback resolves by client_order_id —
    // not findByIntentKey, which would now miss the bounded id and drop the race-window fill.
    Optional<JournaledOrder> row = journal.findByBrokerOrderId(event.brokerOrderId());
    if (row.isEmpty() && event.clientOrderId() != null) {
      row = journal.findByClientOrderId(event.clientOrderId());
    }
    if (row.isEmpty()) {
      log.warn(
          "fill-dispatcher unknown broker_order_id={} client_order_id={} source={}; dropping",
          event.brokerOrderId(),
          event.clientOrderId(),
          event.source());
      metrics.recordUnknownOrder();
      return;
    }
    JournaledOrder order = row.get();

    // #244: terminalize the journal to FILLED BEFORE signalling. markFilled is conditional on the
    // row being in (RECORDED, SUBMITTED), so a repeat (WS then POLL, or a re-delivery) is an
    // idempotent no-op and never corrupts qty/price. Doing this before the signal guarantees the
    // row reaches FILLED even if the onFill target has already completed (the previous behaviour
    // only signalled and swallowed WorkflowNotFoundException, leaving the row stranded SUBMITTED).
    //
    // #250: gate terminalization on a COMPLETE fill. Alpaca's WS partial_fill events carry
    // filled_qty as the cumulative-so-far quantity (< order.qty()); terminalizing on a partial
    // would lock the row at the partial qty and lose remaining-qty accounting. Only the terminal
    // WS fill (filledQty == order.qty()) — or the POLL backstop, which never delivers a partial
    // (AlpacaPaperBroker.mapStatus maps partially_filled -> OPEN, only filled -> FILLED) —
    // terminalizes, and it carries the full qty. The onFill signal below is intentionally still
    // sent for partials so partial-fill signalling is preserved.
    if (event.filledQty() >= order.qty()) {
      // #836: attribute the terminalization to the net that delivered it. The conditional
      // markFilled means a WS/POLL redelivery race records whichever actually won the row.
      String detectedVia = event.source() == BrokerFillEvent.Source.POLL ? "poll" : "ws";
      boolean terminalized =
          journal.markFilled(
              order.intentKey(),
              event.filledQty(),
              event.avgFillPrice(),
              event.filledAt(),
              detectedVia);
      if (terminalized) {
        log.info(
            "fill-dispatcher journal terminalized FILLED intent_key={} broker_order_id={} qty={}"
                + " detected_via={}",
            order.intentKey(),
            event.brokerOrderId(),
            event.filledQty(),
            detectedVia);
      }
    } else {
      log.debug(
          "fill-dispatcher partial fill not terminalizing intent_key={} broker_order_id={}"
              + " filled_qty={} order_qty={}",
          order.intentKey(),
          event.brokerOrderId(),
          event.filledQty(),
          order.qty());
    }

    String workflowId = resolveWorkflowId(order);
    FillSignalPayload payload =
        new FillSignalPayload()
            .withBrokerOrderId(event.brokerOrderId())
            .withFilledQty(event.filledQty())
            .withAvgFillPrice(event.avgFillPrice())
            .withFilledAt(event.filledAt());
    WorkflowStub stub = workflowClient.newUntypedWorkflowStub(workflowId);
    try {
      stub.signal(SIGNAL_NAME, payload);
      metrics.recordDispatched();
      log.debug(
          "fill-dispatcher signal accepted workflow_id={} broker_order_id={} qty={}",
          workflowId,
          event.brokerOrderId(),
          event.filledQty());
    } catch (WorkflowNotFoundException e) {
      // #819 Phase B: for an ENTRY intent, the primary target is the PARENT signal workflow,
      // which completes as soon as the entry confirms — a later cumulative slice (the straggler)
      // then lands here and used to be dropped, leaving #801's growth path in PositionWorkflow
      // starved (the 2026-08-25 under-booking's dispatch half). The owning PositionWorkflow's id
      // is fully derivable from the journal row via the SHARED contract identity helper (exec and
      // orchestrator use the same WorkflowIds.position, so the format cannot drift): reroute the
      // signal there once. bookEntryGrowth books the cumulative delta capped at the ordered qty,
      // so a stale or duplicate report books 0. Exit intents keep today's benign log — their
      // fills already route to the position workflow by intent-key prefix.
      String rerouteId = entryRerouteWorkflowId(order);
      if (rerouteId != null) {
        try {
          workflowClient.newUntypedWorkflowStub(rerouteId).signal(SIGNAL_NAME, payload);
          metrics.recordDispatched();
          metrics.recordEntryReroute();
          log.info(
              "fill-dispatcher entry straggler rerouted to position workflow_id={}"
                  + " broker_order_id={} filled_qty={}",
              rerouteId,
              event.brokerOrderId(),
              event.filledQty());
          return;
        } catch (WorkflowNotFoundException alsoGone) {
          // The position workflow is also gone (closed / never spawned) — fall through to the
          // benign log; recon / adoption owns the residue from the terminalized journal row.
        } catch (RuntimeException rerouteFailure) {
          // Goal-review finding 9: a non-NOT_FOUND Temporal failure mid-reroute must be counted
          // like the sibling signal path, not escape uncounted. Callers wrap dispatch, so the
          // rethrow cannot wedge the listener.
          metrics.recordSignalError();
          throw rerouteFailure;
        }
      }
      log.info(
          "fill-dispatcher workflow already completed workflow_id={} broker_order_id={}",
          workflowId,
          event.brokerOrderId());
      metrics.recordSignalWorkflowNotFound();
    } catch (RuntimeException e) {
      metrics.recordSignalError();
      throw e;
    }
  }

  /**
   * #819: the reroute target for an ENTRY intent's straggler fill — the owning PositionWorkflow id,
   * built from the journal row's identity fields through the SHARED contract helper. Returns null
   * for exit intents (`:exit:` marker in the key) and for rows missing any identity field
   * (fail-safe: no reroute, keep the benign-drop path).
   */
  private static String entryRerouteWorkflowId(JournaledOrder order) {
    String intentKey = order.intentKey();
    if (intentKey == null
        || !intentKey.contains(ENTRY_INTENT_KEY_MARKER)
        || intentKey.contains(EXIT_INTENT_KEY_MARKER)) {
      return null;
    }
    return WorkflowIds.position(
        order.tenantId(), order.strategyId(), order.optionSymbol(), order.signalId());
  }

  private static String resolveWorkflowId(JournaledOrder order) {
    String intentKey = order.intentKey();
    int marker = intentKey.indexOf(EXIT_INTENT_KEY_MARKER);
    if (marker > 0) {
      // STC fill — intent_key prefix IS the position workflow ID by construction
      // (PositionWorkflowImpl: `Workflow.getInfo().getWorkflowId() + ":exit:" + signalId`).
      return intentKey.substring(0, marker);
    }
    // Entry (BTO) fill. Every entry intent_key is `<owning-workflow-id>:entry`. For a WATCHLIST leg
    // the owning id (`.../wl/{et_date}/{ticker}/{C|P}`) can NOT be reconstructed from the row's
    // signalId, so route by stripping the `:entry` suffix (endsWith, not indexOf — the prefix may
    // itself contain the substring). Scoped strictly to `/wl/` prefixes so copytrade (and any other
    // entry) keeps the byte-identical reconstruct routing below — a zero-regression guarantee for
    // the real-money copytrade path. Note prefix-strip and reconstruct actually AGREE for copytrade
    // (`copytradeSignal` is a pure concat of the same signalId), but branching avoids relying on
    // that equivalence surviving any future signalId sanitization at the workflow-start site.
    if (intentKey.endsWith(ENTRY_INTENT_KEY_MARKER)) {
      String owner = intentKey.substring(0, intentKey.length() - ENTRY_INTENT_KEY_MARKER.length());
      if (owner.contains(WATCHLIST_ID_SEGMENT)) {
        return owner;
      }
    }
    return WorkflowIds.copytradeSignal(order.tenantId(), order.strategyId(), order.signalId());
  }
}
