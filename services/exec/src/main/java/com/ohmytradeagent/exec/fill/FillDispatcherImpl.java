package com.ohmytradeagent.exec.fill;

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
 * Resolves a broker-reported {@link BrokerFillEvent} back to the originating {@code
 * CopytradeSignalWorkflow} and signals {@code onFill}. The resolution chain is:
 *
 * <ol>
 *   <li>{@link OrderIntentJournal#findByBrokerOrderId(String)} → {@link JournaledOrder} (returns
 *       {@code empty} for unknown orders — counted + dropped, not propagated).
 *   <li>{@link WorkflowIds#copytradeSignal(String, String, String)} builds the deterministic
 *       workflow ID matching the Python emitter's shape.
 *   <li>{@link WorkflowClient#newUntypedWorkflowStub(String)} signals {@code onFill}. The untyped
 *       stub avoids dragging the {@code CopytradeSignalWorkflow} interface across the
 *       exec/orchestrator module boundary; Temporal's Jackson data converter rehydrates the {@link
 *       FillSignalPayload} JSON into the orchestrator's own {@code FillEvent} record.
 * </ol>
 *
 * <p>Registered as the active {@link FillDispatcher} bean; {@link NoopFillDispatcher} falls back
 * via {@code @ConditionalOnMissingBean} only in contexts where this bean is excluded
 * (transport-only smoke tests, etc).
 *
 * <p>At-least-once contract: the workflow's {@code onFill} handler MUST tolerate replays. A second
 * fill arriving after the workflow has already moved on is benign (the field is overwritten and
 * never read).
 */
@Component
public class FillDispatcherImpl implements FillDispatcher {

  private static final Logger log = LoggerFactory.getLogger(FillDispatcherImpl.class);
  private static final String SIGNAL_NAME = "onFill";

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
    Optional<JournaledOrder> row = journal.findByBrokerOrderId(event.brokerOrderId());
    if (row.isEmpty()) {
      log.warn(
          "fill-dispatcher unknown broker_order_id={} source={}; dropping",
          event.brokerOrderId(),
          event.source());
      metrics.recordUnknownOrder();
      return;
    }
    JournaledOrder order = row.get();
    String workflowId =
        WorkflowIds.copytradeSignal(order.tenantId(), order.strategyId(), order.signalId());
    FillSignalPayload payload =
        new FillSignalPayload(
            event.brokerOrderId(), event.filledQty(), event.avgFillPrice(), event.filledAt());
    WorkflowStub stub = workflowClient.newUntypedWorkflowStub(workflowId);
    try {
      stub.signal(SIGNAL_NAME, payload);
      log.debug(
          "fill-dispatcher signal accepted workflow_id={} broker_order_id={} qty={}",
          workflowId,
          event.brokerOrderId(),
          event.filledQty());
    } catch (WorkflowNotFoundException e) {
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
}
