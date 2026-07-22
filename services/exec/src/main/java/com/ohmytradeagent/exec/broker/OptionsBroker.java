package com.ohmytradeagent.exec.broker;

import com.ohmytradeagent.contract.BrokerOpenOrder;
import com.ohmytradeagent.contract.BrokerPosition;
import com.ohmytradeagent.contract.PreTradeCheckRequest;
import com.ohmytradeagent.contract.PreTradeCheckResult;
import java.math.BigDecimal;
import java.time.LocalDate;
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

  /**
   * Combined account read for the notional-cap gate (issue #323). Returns equity AND cash from a
   * single account fetch so the {@code AccountSnapshotActivity} does not pay two {@code
   * /v2/account} round-trips per invocation (one for equity, one for cash). The gate's MTM-stable
   * denominator is {@code cash + sum_open_notional} (cost basis) while {@code equity} is retained
   * for the #317 fail-closed contract.
   *
   * <p>Default composes the two single-field getters so brokers that don't expose a real account
   * endpoint keep the documented {@code ZERO} sentinel behavior (fail closed). Brokers that hit a
   * live account endpoint (Alpaca {@code /v2/account}) override this to fetch the account once and
   * extract both fields.
   */
  default AccountSummary getAccount() {
    return new AccountSummary(getAccountEquity(), getAccountCash(), null);
  }

  /**
   * Equity + cash from one account read (issue #323). All figures in account-currency dollars.
   * {@code accountNumber} is informational (brokerage account identity for the tenant dashboard),
   * nullable, and not used by any gate. {@code lastEquity} is the prior market-close
   * net-liquidation equity (Alpaca {@code /v2/account 'last_equity'}); it backs the dashboard's
   * live intraday "today" figure ({@code equity - lastEquity}). Also informational, nullable, and
   * not used by any gate — a broker that does not expose it leaves the downstream {@code today_pl}
   * unavailable.
   */
  record AccountSummary(
      BigDecimal equity, BigDecimal cash, String accountNumber, BigDecimal lastEquity) {

    /**
     * Back-compat convenience for brokers/tests that carry no {@code lastEquity} — delegates with a
     * null prior-close equity (the dashboard's "today" figure then falls back to the last completed
     * daily bar rather than fabricating an intraday number).
     */
    public AccountSummary(BigDecimal equity, BigDecimal cash, String accountNumber) {
      this(equity, cash, accountNumber, null);
    }
  }

  /**
   * Trading days in {@code [start, end]} inclusive, per the broker's market calendar. Used by the
   * watchlist-trigger expiry resolver to holiday-shift a candidate Friday to the preceding trading
   * day.
   *
   * <p>Default throws {@link UnsupportedOperationException} so only brokers that expose a calendar
   * endpoint (Alpaca {@code GET /v2/calendar}) support it; the in-memory {@link
   * com.ohmytradeagent.exec.broker.stub.StubBroker} and other adapters are unaffected until they
   * opt in.
   */
  default List<LocalDate> tradingDays(LocalDate start, LocalDate end) {
    throw new UnsupportedOperationException("tradingDays not supported by this broker");
  }

  /**
   * Live-account-view: the brokerage account's portfolio-history series (Alpaca {@code GET
   * /v2/account/portfolio/history}) for the dashboard {@code /live} equity chart. A READ-ONLY GET —
   * it places no orders and touches no order path.
   *
   * <p>{@code period} / {@code timeframe} are already-resolved Alpaca values (the BFF client owns
   * the dashboard-range mapping); {@code dateEnd} may be null (omit the {@code date_end} query
   * param when so). Returns parallel arrays indexed by epoch-second {@code timestamps} ({@code
   * equity} chart line, {@code profitLoss} / {@code profitLossPct} headline) plus the {@code
   * baseValue} baseline.
   *
   * <p>Default throws {@link UnsupportedOperationException} so only brokers that expose the
   * endpoint (Alpaca) support it; the in-memory {@link
   * com.ohmytradeagent.exec.broker.stub.StubBroker} and other adapters are unaffected until they
   * opt in. Because it is {@code default} (not abstract), adding it does not break any existing
   * adapter's compilation.
   */
  default PortfolioHistory getPortfolioHistory(String period, String timeframe, String dateEnd) {
    throw new UnsupportedOperationException("getPortfolioHistory not supported by this broker");
  }

  /**
   * Portfolio-history series from one account read. Parallel arrays are indexed by {@code
   * timestamps} (epoch seconds): {@code equity} (chart line), {@code profitLoss}, {@code
   * profitLossPct}. {@code baseValue} is the baseline (dashed line / range start), {@code
   * baseValueAsof} its epoch-second as-of (nullable), {@code timeframe} the resolved Alpaca
   * timeframe. Account-level (shared across tenants on a broker_target) and never a risk-gate
   * input.
   */
  record PortfolioHistory(
      long[] timestamps,
      BigDecimal[] equity,
      BigDecimal[] profitLoss,
      BigDecimal[] profitLossPct,
      BigDecimal baseValue,
      Long baseValueAsof,
      String timeframe) {}

  /**
   * Live-account-view: the brokerage account's cash flows (deposits {@code CSD}, withdrawals {@code
   * CSW}, cash journals {@code JNLC}) over {@code [startEpochSec, endEpochSec]} (Alpaca {@code GET
   * /v2/account/activities}), so the BFF can compute a deposit-adjusted range return. A READ-ONLY
   * GET — it places no orders and touches no order path.
   *
   * <p>Default throws {@link UnsupportedOperationException} so only brokers that expose the
   * endpoint (Alpaca) support it; the in-memory {@link
   * com.ohmytradeagent.exec.broker.stub.StubBroker} and other adapters are unaffected until they
   * opt in. Because it is {@code default} (not abstract), adding it does not break any existing
   * adapter's compilation.
   */
  default List<AccountCashFlow> getAccountActivities(long startEpochSec, long endEpochSec) {
    throw new UnsupportedOperationException("getAccountActivities not supported by this broker");
  }

  /**
   * One account cash flow: {@code timestamp} (epoch seconds) and {@code amount} (Alpaca {@code
   * net_amount}; deposit {@code +}, withdrawal {@code −}). Account-level (shared across tenants on
   * a broker_target) and never a risk-gate input.
   */
  record AccountCashFlow(long timestamp, BigDecimal amount) {}
}
