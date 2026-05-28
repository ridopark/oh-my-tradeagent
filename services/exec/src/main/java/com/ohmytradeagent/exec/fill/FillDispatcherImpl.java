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
 *   <li><b>BTO fills</b> (intent_key does not contain {@code :exit:}): route to the {@code
 *       CopytradeSignalWorkflow} that placed the entry order, via {@link
 *       WorkflowIds#copytradeSignal(String, String, String)}.
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
    // client_order_id (== intent_key, set at upsertIntent and passed to the broker). The fallback
    // closes the ~26ms submit/fill race: a WS fill can arrive AFTER broker.placeOrder returns but
    // BEFORE ExecActivitiesImpl.placeOrder runs markSubmittedIfRecorded(intentKey, brokerOrderId),
    // so the row carries no broker_order_id yet and findByBrokerOrderId is empty. Without the
    // fallback the fill was logged unknown + dropped, leaving the row stuck SUBMITTED and stranding
    // the position. Only a fill that resolves by NEITHER key is a genuine unknown.
    Optional<JournaledOrder> row = journal.findByBrokerOrderId(event.brokerOrderId());
    if (row.isEmpty() && event.clientOrderId() != null) {
      row = journal.findByIntentKey(event.clientOrderId());
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
    boolean terminalized =
        journal.markFilled(
            order.intentKey(), event.filledQty(), event.avgFillPrice(), event.filledAt());
    if (terminalized) {
      log.info(
          "fill-dispatcher journal terminalized FILLED intent_key={} broker_order_id={} qty={}",
          order.intentKey(),
          event.brokerOrderId(),
          event.filledQty());
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
      // Benign: the journal is already terminalized above, so the position is not stranded — recon
      // / adoption can spawn the owner workflow from the FILLED row.
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

  private static String resolveWorkflowId(JournaledOrder order) {
    String intentKey = order.intentKey();
    int marker = intentKey.indexOf(EXIT_INTENT_KEY_MARKER);
    if (marker > 0) {
      // STC fill — intent_key prefix IS the position workflow ID by construction
      // (PositionWorkflowImpl: `Workflow.getInfo().getWorkflowId() + ":exit:" + signalId`).
      return intentKey.substring(0, marker);
    }
    return WorkflowIds.copytradeSignal(order.tenantId(), order.strategyId(), order.signalId());
  }
}
