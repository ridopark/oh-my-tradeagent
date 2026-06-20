package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.SubscribeEquityRequest;
import com.ohmytradeagent.contract.SubscribeEquityResult;
import io.temporal.activity.ActivityInterface;

/**
 * Orchestrator-side stub for the market-data service's @ActivityInterface. The implementation lives
 * in {@code services/market-data}; Temporal routes by activity name + task queue ({@code
 * market-data}) — no shared bytecode required.
 *
 * <p>This is intentionally a SECOND {@code @ActivityInterface} mirroring the contract copy ({@code
 * com.ohmytradeagent.contract.activities.SubscribeEquityActivity}), NOT a duplicate to consolidate.
 * It follows the established streaming/quote dual-stub convention also used by {@link
 * SubscribePremiumActivity} and {@link GetOptionQuoteActivity}: the orchestrator declares a typed
 * stub for {@code Workflow.newActivityStub}, the market-data worker implements the contract copy,
 * and Temporal routes by activity name + task queue.
 */
@ActivityInterface
public interface SubscribeEquityActivity {

  SubscribeEquityResult subscribeEquity(SubscribeEquityRequest req);
}
