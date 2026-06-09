package com.ohmytradeagent.contract.activities;

import com.ohmytradeagent.contract.GetOptionQuoteRequest;
import com.ohmytradeagent.contract.OptionQuoteResult;
import io.temporal.activity.ActivityInterface;

/**
 * Plan-2A R-AA-2 cross-service contract. Implementation lives in {@code services/market-data};
 * consumers (the orchestrator's {@code PositionWorkflow}) declare a workflow stub against this
 * interface with the {@code market-data} task queue. Temporal routes by activity name + task queue.
 *
 * <p>Pure read: {@code getOptionQuote} returns a one-shot NBBO snapshot. It returns {@code
 * status=FAILED}/{@code UNAVAILABLE} instead of throwing so the bounded scheduled-flatten path
 * (eod/expiry/chandelier) can fall back to a marketable exit and emit a loud availability audit
 * without going into Temporal retry. Mirrors the {@link SubscribePremiumActivity} precedent and
 * shares its {@code market-data} task queue.
 */
@ActivityInterface
public interface GetOptionQuoteActivity {

  OptionQuoteResult getOptionQuote(GetOptionQuoteRequest req);
}
