package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.ArmContext;
import com.ohmytradeagent.contract.FireDecision;
import com.ohmytradeagent.contract.WatchlistTriggerPayload;
import io.temporal.activity.ActivityInterface;

/**
 * Fire-time decision hook for an armed watchlist trigger: decides whether to proceed with the order
 * and with what size multiplier. {@link ArmContext} is the minimal shared context (reused from the
 * arm hook); it is additively versionable so future AI inputs can be threaded in without changing
 * this signature.
 */
@ActivityInterface
public interface TriggerFireDecider {

  FireDecision evaluateTriggerFire(WatchlistTriggerPayload entry, ArmContext ctx);
}
