package com.ohmytradeagent.exec.broker;

import com.ohmytradeagent.contract.BrokerOpenOrder;
import com.ohmytradeagent.contract.PreTradeCheckRequest;
import com.ohmytradeagent.contract.PreTradeCheckResult;
import java.math.BigDecimal;
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

  /**
   * Attempt to cancel the order at the broker. Returns a 3-state outcome:
   *
   * <ul>
   *   <li>{@link CancelResponse.Outcome#CANCELLED} — broker confirmed the cancel.
   *   <li>{@link CancelResponse.Outcome#FAILED} — non-fill rejection (validation / unknown id /
   *       transient 4xx). Caller records the broker reason on the journal row.
   *   <li>{@link CancelResponse.Outcome#ALREADY_FILLED} — issue #165: broker rejected the cancel
   *       because the order had already filled. Caller fetches {@link #getFillDetail(String)} and
   *       reconciles the journal to FILLED.
   * </ul>
   */
  CancelResponse cancelOrder(String brokerOrderId);

  BrokerOrderStatus getOrderStatus(String brokerOrderId);

  /**
   * Issue #165: fetch broker-confirmed fill detail (qty, avg price, fill time) for an order the
   * broker reports as already-filled. Only called from the cancel-on-filled reconciliation path —
   * implementations should treat a non-filled or partially-filled response as a protocol error (the
   * caller has already seen the broker classify the order as filled).
   */
  BrokerFillDetail getFillDetail(String brokerOrderId);

  /**
   * Phase 5 reconciliation: list currently-open broker orders. Default returns an empty list so the
   * Phase 5 reconciliation workflow degrades cleanly against brokers that don't expose this yet
   * (the in-memory {@link com.ohmytradeagent.exec.broker.stub.StubBroker}, or providers whose
   * listing endpoint is deferred).
   */
  default List<BrokerOpenOrder> listOpenOrders() {
    return List.of();
  }

  /**
   * Issue #6 portfolio-level gate. Default returns a permissive result: {@code allowed=true},
   * sentinel buying-power above any realistic notional, PDT OK, margin sufficient. Brokers that
   * expose a real pre-trade endpoint (Alpaca account/buying-power, Tradier balances) override this
   * to query their venue.
   *
   * <p>The orchestrator's risk gate is also opt-in via {@code
   * StrategyConfig.pre_trade_check_enabled}, so a deployment running the default impl never
   * surprises a strategy that didn't enable the gate.
   */
  default PreTradeCheckResult preTradeCheck(PreTradeCheckRequest request) {
    PreTradeCheckResult r = new PreTradeCheckResult();
    r.setSchemaVersion(1L);
    r.setAllowed(true);
    r.setBuyingPower(new BigDecimal("1000000000"));
    r.setPdtStatus(PreTradeCheckResult.PdtStatus.OK);
    r.setMarginSufficient(true);
    return r;
  }
}
