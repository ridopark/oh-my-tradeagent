package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.SubscribeEquityRequest;
import com.ohmytradeagent.contract.SubscribeEquityResult;
import io.temporal.activity.ActivityInterface;

/**
 * Orchestrator-side stub for the market-data service's @ActivityInterface. The implementation lives
 * in {@code services/market-data}; Temporal routes by activity name + task queue ({@code
 * market-data}) — no shared bytecode required.
 */
@ActivityInterface
public interface SubscribeEquityActivity {

  SubscribeEquityResult subscribeEquity(SubscribeEquityRequest req);
}
