package com.ohmytradeagent.exec.activities;

import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.contract.OrderIntentResult;
import com.ohmytradeagent.exec.broker.BrokerFillDetail;
import com.ohmytradeagent.exec.broker.CancelResponse;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import com.ohmytradeagent.exec.broker.PlaceOrderRequest;
import com.ohmytradeagent.exec.broker.PlaceOrderResponse;
import com.ohmytradeagent.exec.journal.JournaledOrder;
import com.ohmytradeagent.exec.journal.OrderIntentJournal;
import com.ohmytradeagent.exec.journal.OrderState;
import io.temporal.failure.ApplicationFailure;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;

/**
 * The Phase 2b implementation of the plan's 3-layer idempotency model (line 362). Each method
 * short-circuits on the journal's current state so repeated Activity attempts (Temporal retries,
 * orchestrator-svc restarts, concurrent task-queue workers) converge on a single broker order per
 * {@code intent_key}.
 *
 * <p>{@link #placeOrder} flow:
 *
 * <pre>
 *   1. journal.upsertIntent(intent)                  // INSERT ON CONFLICT DO NOTHING
 *   2. journal.findByIntentKey                       // read canonical row
 *   3. if state == SUBMITTED       → return existing (skip broker call)
 *   4. broker.placeOrder(client_order_id = intent_key)   // idempotent on client_order_id
 *   5. journal.markSubmittedIfRecorded               // WHERE state = 'RECORDED'
 *   6. return SUBMITTED result
 * </pre>
 */
@Component
public class ExecActivitiesImpl implements ExecActivities {

  private final OrderIntentJournal journal;
  private final OptionsBroker broker;

  public ExecActivitiesImpl(OrderIntentJournal journal, OptionsBroker broker) {
    this.journal = journal;
    this.broker = broker;
  }

  @Override
  public OrderIntentResult placeOrder(OrderIntent intent) {
    journal.upsertIntent(intent);
    JournaledOrder row =
        journal
            .findByIntentKey(intent.getIntentKey())
            .orElseThrow(
                () ->
                    ApplicationFailure.newNonRetryableFailure(
                        "Journal row vanished after upsert: " + intent.getIntentKey(),
                        "JournalConsistencyError"));

    if (row.state() == OrderState.SUBMITTED) {
      return result(row);
    }

    PlaceOrderResponse br =
        broker.placeOrder(
            new PlaceOrderRequest(
                intent.getIntentKey(),
                intent.getOptionSymbol(),
                intent.getSide().value(),
                intent.getQty(),
                intent.getLimitPrice()));

    journal.markSubmittedIfRecorded(intent.getIntentKey(), br.brokerOrderId());

    return journal
        .findByIntentKey(intent.getIntentKey())
        .map(ExecActivitiesImpl::result)
        .orElseThrow(
            () ->
                ApplicationFailure.newNonRetryableFailure(
                    "Journal row missing post-mark: " + intent.getIntentKey(),
                    "JournalConsistencyError"));
  }

  @Override
  public OrderIntentResult cancelOrder(String intentKey) {
    JournaledOrder row =
        journal
            .findByIntentKey(intentKey)
            .orElseThrow(
                () ->
                    ApplicationFailure.newNonRetryableFailure(
                        "Cannot cancel: no journal row for " + intentKey,
                        "JournalConsistencyError"));

    if (row.state() == OrderState.CANCELLED) {
      return result(row);
    }
    if (row.brokerOrderId() == null) {
      // Pre-broker cancel: just flip state, nothing to call.
      journal.markCancelled(intentKey);
      return journal.findByIntentKey(intentKey).map(ExecActivitiesImpl::result).orElseThrow();
    }

    journal.markCancelAttempted(intentKey);
    CancelResponse cancel = broker.cancelOrder(row.brokerOrderId());
    switch (cancel.outcome()) {
      case CANCELLED -> journal.markCancelled(intentKey);
      case FAILED -> journal.markCancelFailed(intentKey, cancel.brokerReason());
      case ALREADY_FILLED -> {
        // Issue #165: the broker filled the order while the cancel was in flight. Pull the
        // broker-confirmed fill detail and reconcile the journal to FILLED so the orchestrator
        // can spawn the missing PositionWorkflow instead of orphaning the position. A repeat
        // call lands as a no-op (markFilled is conditional on RECORDED/SUBMITTED state).
        BrokerFillDetail fill = broker.getFillDetail(row.brokerOrderId());
        journal.markFilled(intentKey, fill.filledQty(), fill.avgFillPrice(), fill.filledAt());
      }
    }
    return journal.findByIntentKey(intentKey).map(ExecActivitiesImpl::result).orElseThrow();
  }

  @Override
  public OrderIntentResult getOrderStatus(String intentKey) {
    JournaledOrder row =
        journal
            .findByIntentKey(intentKey)
            .orElseThrow(
                () ->
                    ApplicationFailure.newNonRetryableFailure(
                        "No journal row for " + intentKey, "JournalConsistencyError"));
    return result(row);
  }

  private static OrderIntentResult result(JournaledOrder row) {
    OrderIntentResult r = new OrderIntentResult();
    r.setSchemaVersion(1L);
    r.setIntentKey(row.intentKey());
    r.setBrokerOrderId(row.brokerOrderId());
    r.setState(OrderIntentResult.State.fromValue(row.state().name()));
    r.setLastStateAt(row.lastStateAt() != null ? row.lastStateAt() : OffsetDateTime.now());
    r.setLastError(row.lastError());
    // Issue #165 phase 2: surface broker-confirmed fill detail so the orchestrator can synthesise
    // a FillEvent on the cancel-on-filled recovery path. Nullable for non-FILLED rows.
    r.setFilledQty(row.filledQty());
    r.setAvgFillPrice(row.avgFillPrice());
    return r;
  }
}
