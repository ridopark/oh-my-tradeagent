package com.ohmytradeagent.exec.broker.stub;

import com.ohmytradeagent.exec.broker.BrokerOrderStatus;
import com.ohmytradeagent.exec.broker.CancelResponse;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import com.ohmytradeagent.exec.broker.PlaceOrderRequest;
import com.ohmytradeagent.exec.broker.PlaceOrderResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Phase 2b reference broker — fully in-memory, idempotent on {@code client_order_id}. Used for the
 * Done-when crash-restart idempotency IT and any environment that wants a network-free exec stack.
 *
 * <p>Deterministic ID scheme: {@code broker_order_id = "stub-" + client_order_id}. This makes test
 * assertions trivial and avoids UUID time-bombs in golden fixtures.
 */
@Component
@ConditionalOnProperty(name = "broker.impl", havingValue = "stub", matchIfMissing = true)
public class StubBroker implements OptionsBroker {

  private final Map<String, BrokerOrderStatus> statusByBrokerOrderId = new ConcurrentHashMap<>();

  @Override
  public PlaceOrderResponse placeOrder(PlaceOrderRequest request) {
    String brokerOrderId = "stub-" + request.clientOrderId();
    BrokerOrderStatus prior =
        statusByBrokerOrderId.putIfAbsent(brokerOrderId, BrokerOrderStatus.OPEN);
    return new PlaceOrderResponse(brokerOrderId, prior != null);
  }

  @Override
  public CancelResponse cancelOrder(String brokerOrderId) {
    BrokerOrderStatus current = statusByBrokerOrderId.get(brokerOrderId);
    if (current == null) {
      return CancelResponse.failed("unknown broker_order_id");
    }
    if (current == BrokerOrderStatus.FILLED) {
      return CancelResponse.failed("order already filled");
    }
    statusByBrokerOrderId.put(brokerOrderId, BrokerOrderStatus.CANCELLED);
    return CancelResponse.ok();
  }

  @Override
  public BrokerOrderStatus getOrderStatus(String brokerOrderId) {
    return statusByBrokerOrderId.getOrDefault(brokerOrderId, BrokerOrderStatus.UNKNOWN);
  }

  /** Test seam: force a status (e.g., FILLED) to exercise the cancel-on-filled path. */
  public void forceStatusForTest(String brokerOrderId, BrokerOrderStatus status) {
    statusByBrokerOrderId.put(brokerOrderId, status);
  }
}
