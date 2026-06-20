package com.ohmytradeagent.contract.activities;

import com.ohmytradeagent.contract.SubscribeEquityRequest;
import com.ohmytradeagent.contract.SubscribeEquityResult;
import io.temporal.activity.ActivityInterface;

/**
 * Phase 2 (watchlist-trigger) cross-service contract. Implementation lives in {@code
 * services/market-data}; consumers (the orchestrator's {@code WatchlistTriggerWorkflow}) declare a
 * workflow stub against this interface with the {@code market-data} task queue. Temporal routes by
 * activity name + task queue.
 *
 * <p>{@code subscribeEquity} returns FAILED/GATED instead of throwing so the workflow can audit and
 * continue without a feed. Mirrors the {@link SubscribePremiumActivity} precedent and shares its
 * {@code market-data} task queue.
 */
@ActivityInterface
public interface SubscribeEquityActivity {

  SubscribeEquityResult subscribeEquity(SubscribeEquityRequest req);
}
