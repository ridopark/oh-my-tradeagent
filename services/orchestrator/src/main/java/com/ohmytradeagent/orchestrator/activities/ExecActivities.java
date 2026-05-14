package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.contract.OrderIntentResult;
import io.temporal.activity.ActivityInterface;

/**
 * Orchestrator-side stub for the exec service's @ActivityInterface. The implementation lives in
 * {@code services/exec-tradier-paper}; Temporal routes by activity name (method name) and task
 * queue (broker-tradier-paper) — no shared bytecode is required, only matching signatures.
 */
@ActivityInterface
public interface ExecActivities {

  OrderIntentResult placeOrder(OrderIntent intent);

  OrderIntentResult cancelOrder(String intentKey);

  OrderIntentResult getOrderStatus(String intentKey);
}
