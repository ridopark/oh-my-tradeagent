package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.contract.OrderIntentResult;
import io.temporal.activity.ActivityInterface;

/**
 * Orchestrator-side stub for the exec service's @ActivityInterface. The implementation lives in
 * {@code services/exec}; Temporal routes by activity name (method name) and task queue (Phase 2c.2:
 * {@code broker-<broker_target>}, e.g. {@code broker-alpaca-paper}, derived via {@link
 * com.ohmytradeagent.orchestrator.workflows.ExecActivitiesFactory}) — no shared bytecode is
 * required, only matching signatures.
 */
@ActivityInterface
public interface ExecActivities {

  OrderIntentResult placeOrder(OrderIntent intent);

  OrderIntentResult cancelOrder(String intentKey);

  OrderIntentResult getOrderStatus(String intentKey);
}
