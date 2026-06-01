package com.ohmytradeagent.exec.broker;

import com.ohmytradeagent.contract.BrokerOpenOrder;
import com.ohmytradeagent.contract.BrokerPosition;
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
   * Issue #165 Phase 3: list option positions currently held at the broker. Used by reconciliation
   * to detect filled-but-no-workflow orphans (a broker position that the orchestrator never spawned
   * a {@code PositionWorkflow} for). Implementations MUST filter to option positions (e.g. Alpaca
   * {@code asset_class="us_option"}) and exclude any equity holdings — a copytrade strategy does
   * not manage equity. Default returns an empty list so brokers without an implementation degrade
   * cleanly (the Phase 3 recon loop just observes zero positions).
   */
  default List<BrokerPosition> listOpenPositions() {
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

  /**
   * Account-equity gate. Returns the brokerage account's net-liquidation equity in dollars (Alpaca
   * {@code /v2/account} {@code equity}, NOT {@code buying_power}). The {@code
   * notional_cap_pct_of_equity} risk gate compares {@code (sum_open_notional + new_notional)}
   * against {@code pct * equity}.
   *
   * <p>Default returns the documented sentinel {@link BigDecimal#ZERO} so brokers that don't yet
   * expose an account endpoint (the in-memory {@link
   * com.ohmytradeagent.exec.broker.stub.StubBroker}) degrade cleanly: zero equity makes the risk
   * gate fail closed (reject) rather than passing an unbounded cap. Brokers that expose a real
   * account endpoint (Alpaca {@code /v2/account}) override this to query their venue.
   *
   * <p>The risk gate is also opt-in via {@code StrategyConfig.notional_cap_pct_of_equity}, so a
   * deployment running the default impl never surprises a strategy that didn't enable the gate.
   */
  default BigDecimal getAccountEquity() {
    return BigDecimal.ZERO;
  }

  /**
   * Issue #323 capital-base gate. Returns the brokerage account's cash balance in dollars (Alpaca
   * {@code /v2/account} {@code cash}, NOT {@code buying_power}). The {@code
   * notional_cap_pct_of_equity} risk gate's MTM-stable denominator is the cost-basis capital base
   * {@code cash + sum_open_notional}, so the cap gate reads cash rather than net-liq equity to keep
   * numerator and denominator on the same cost basis.
   *
   * <p>Default returns the documented sentinel {@link BigDecimal#ZERO} so brokers that don't yet
   * expose an account endpoint degrade cleanly: zero cash makes the cap gate fail closed (reject)
   * rather than passing an unbounded cap. Brokers with a real account endpoint override this.
   */
  default BigDecimal getAccountCash() {
    return BigDecimal.ZERO;
  }
}
