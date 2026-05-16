package com.ohmytradeagent.contract.activities;

import com.ohmytradeagent.contract.PreTradeCheckRequest;
import com.ohmytradeagent.contract.PreTradeCheckResult;
import io.temporal.activity.ActivityInterface;

/**
 * Issue #6 cross-service contract owned by exec-svc (one impl per &lt;provider&gt;-&lt;env&gt;
 * pair). risk-svc dispatches the call to the {@code broker-<broker_target>} task queue, matching
 * the routing already in place for {@link ReconciliationExecActivity} and {@code placeOrder}.
 *
 * <p>Semantics: the broker adapter reports {@code buying_power}, {@code pdt_status}, and {@code
 * margin_sufficient} for the requested side+qty+notional. risk-svc rejects the entry when {@code
 * allowed=false}, {@code buying_power < estimated_notional}, {@code pdt_status='BLOCKED'}, or
 * {@code margin_sufficient=false}. Any exception is treated as fail-closed by the caller, mirroring
 * the kill-switch read.
 *
 * <p>Default broker implementations that do not yet expose a real pre-trade endpoint return {@code
 * allowed=true} with sentinel values so the wire shape is stable while per-provider backfills land.
 * The gate is also opt-in via {@code StrategyConfig.pre_trade_check_enabled}, so the cross-service
 * round-trip only fires when the strategy explicitly enabled it.
 */
@ActivityInterface
public interface PreTradeCheckActivity {

  PreTradeCheckResult preTradeCheck(PreTradeCheckRequest request);
}
