package com.ohmytradeagent.exec.activities;

import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.contract.OrderIntentResult;
import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface ExecActivities {

  OrderIntentResult placeOrder(OrderIntent intent);

  OrderIntentResult cancelOrder(String intentKey);

  OrderIntentResult getOrderStatus(String intentKey);
}
