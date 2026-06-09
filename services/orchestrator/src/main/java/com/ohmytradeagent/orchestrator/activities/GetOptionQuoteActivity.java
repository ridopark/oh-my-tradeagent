package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.GetOptionQuoteRequest;
import com.ohmytradeagent.contract.OptionQuoteResult;
import io.temporal.activity.ActivityInterface;

/**
 * Plan-2A R-AA-2 orchestrator-side stub for the market-data service's {@code GetOptionQuote}
 * activity. The implementation lives in {@code services/market-data}; Temporal routes by activity
 * name + task queue ({@code market-data}) — no shared bytecode required. Mirrors the {@link
 * SubscribePremiumActivity} precedent (the same pattern the contract module's {@code
 * com.ohmytradeagent.contract.activities.GetOptionQuoteActivity} declares for the worker side).
 *
 * <p>Pure read: returns {@code status=OK/UNAVAILABLE/FAILED} and never throws, so the bounded
 * scheduled-flatten path can fall back to a marketable exit + audit instead of going into Temporal
 * retry.
 */
@ActivityInterface
public interface GetOptionQuoteActivity {

  OptionQuoteResult getOptionQuote(GetOptionQuoteRequest req);
}
