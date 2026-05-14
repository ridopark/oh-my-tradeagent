package com.ohmytradeagent.contract.activities;

import com.ohmytradeagent.contract.SubscribePremiumRequest;
import com.ohmytradeagent.contract.SubscribePremiumResult;
import io.temporal.activity.ActivityInterface;

/**
 * Phase 4 cross-service contract. Implementation lives in {@code services/market-data}; consumers
 * (the orchestrator's {@code PositionWorkflow}) declare a workflow stub against this interface with
 * the {@code market-data} task queue. Temporal routes by activity name + task queue.
 *
 * <p>{@code subscribePremium} returns FAILED instead of throwing so the workflow can audit and
 * continue without a trail when the source is unavailable.
 */
@ActivityInterface
public interface SubscribePremiumActivity {

  SubscribePremiumResult subscribePremium(SubscribePremiumRequest req);
}
