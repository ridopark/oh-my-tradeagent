package com.ohmytradeagent.contract.activities;

import com.ohmytradeagent.contract.PortfolioHistoryRequest;
import com.ohmytradeagent.contract.PortfolioHistoryResult;
import io.temporal.activity.ActivityInterface;

/**
 * Live-account-view contract owned by exec-svc (one impl per &lt;provider&gt;-&lt;env&gt; pair).
 * The tenant-dashboard BFF starts the {@code PortfolioHistoryWorkflow}, which dispatches this call
 * to the {@code broker-<broker_target>} task queue — matching the routing already in place for
 * {@link AccountSnapshotActivity}.
 *
 * <p>Semantics: the broker adapter reads the account's Alpaca {@code /v2/account/portfolio/history}
 * series (parallel arrays indexed by epoch-second {@code timestamps}: {@code equity} chart line,
 * {@code profit_loss} / {@code profit_loss_pct} range headline; plus the {@code base_value}
 * baseline). This is a READ-ONLY GET — it places no orders and touches no order path. Equity is
 * account-level — tenant/strategy-independent — so the request is keyed solely on {@code
 * broker_target}; every {@code (tenant, strategy)} routing to a given {@code broker_target} shares
 * one account and observes the same history.
 *
 * <p>The {@code period}/{@code timeframe} are ALREADY RESOLVED by the BFF client (the {@code
 * range}->{@code period}/{@code timeframe} mapping, incl. the YTD date calc, runs there) so the
 * workflow and activity stay a dumb, replay-stable pass-through.
 */
@ActivityInterface
public interface PortfolioHistoryActivity {

  PortfolioHistoryResult portfolioHistory(PortfolioHistoryRequest request);
}
