package com.ohmytradeagent.contract.activities;

import com.ohmytradeagent.contract.AccountSnapshotRequest;
import com.ohmytradeagent.contract.AccountSnapshotResult;
import io.temporal.activity.ActivityInterface;

/**
 * Cross-service contract owned by exec-svc (one impl per &lt;provider&gt;-&lt;env&gt; pair).
 * risk-svc dispatches the call to the {@code broker-<broker_target>} task queue, matching the
 * routing already in place for {@link PreTradeCheckActivity}, {@link ReconciliationExecActivity},
 * and {@code placeOrder}.
 *
 * <p>Semantics: the broker adapter reports the account's net-liquidation {@code equity} (Alpaca
 * {@code /v2/account} {@code equity}, NOT {@code buying_power}). Equity is account-level —
 * tenant/strategy-independent — so the request is keyed solely on {@code broker_target}; every
 * {@code (tenant, strategy)} routing to a given {@code broker_target} shares one account and
 * observes the same equity. risk-svc threads the equity into the {@code
 * notional_cap_pct_of_capital_base} gate. Any exception is treated as fail-closed by the caller
 * (rejecting the entry), mirroring the kill-switch and pre-trade-check reads.
 *
 * <p>Default broker implementations that do not yet expose a real account endpoint return the
 * sentinel {@code equity=0} so the wire shape is stable while per-provider backfills land. The
 * notional-cap gate is also opt-in via {@code StrategyConfig.notional_cap_pct_of_capital_base}, so
 * the cross-service round-trip only fires when the strategy explicitly enabled it.
 */
@ActivityInterface
public interface AccountSnapshotActivity {

  AccountSnapshotResult accountSnapshot(AccountSnapshotRequest request);
}
