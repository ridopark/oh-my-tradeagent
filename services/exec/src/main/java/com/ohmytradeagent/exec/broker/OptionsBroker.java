package com.ohmytradeagent.exec.broker;

import com.ohmytradeagent.contract.BrokerOpenOrder;
import java.util.List;

/**
 * Broker port. Implementations (Tradier sandbox, Tradier live, IBKR, Alpaca, ...) plug in via
 * Spring profiles. The contract callers depend on:
 *
 * <ol>
 *   <li>{@code placeOrder} is idempotent on {@code client_order_id}: a repeated call with the same
 *       client_order_id returns the same broker_order_id with {@code alreadyExisted=true} rather
 *       than placing a duplicate.
 *   <li>{@code cancelOrder} on an already-filled order returns {@code cancelled=false} with a
 *       broker-provided reason; callers treat this as an orphan-position signal, never a silent
 *       success.
 * </ol>
 *
 * <p>The {@link com.ohmytradeagent.exec.broker.stub.StubBroker} enforces these in memory. The
 * {@link com.ohmytradeagent.exec.broker.alpaca.AlpacaPaperBroker} translates them onto Alpaca's
 * Options API, leaning on Alpaca's native {@code client_order_id} dedup for idempotency. Other
 * provider adapters (Tradier sandbox, IBKR, Schwab) land as 2c.x follow-ups.
 */
public interface OptionsBroker {

  PlaceOrderResponse placeOrder(PlaceOrderRequest request);

  CancelResponse cancelOrder(String brokerOrderId);

  BrokerOrderStatus getOrderStatus(String brokerOrderId);

  /**
   * Phase 5 reconciliation: list currently-open broker orders. Default returns an empty list so the
   * Phase 5 reconciliation workflow degrades cleanly against brokers that don't expose this yet
   * (the in-memory {@link com.ohmytradeagent.exec.broker.stub.StubBroker}, or providers whose
   * listing endpoint is deferred).
   */
  default List<BrokerOpenOrder> listOpenOrders() {
    return List.of();
  }
}
