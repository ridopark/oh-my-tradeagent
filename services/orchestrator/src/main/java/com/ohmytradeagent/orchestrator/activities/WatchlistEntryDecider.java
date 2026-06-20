package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.ArmContext;
import com.ohmytradeagent.contract.ArmDecision;
import com.ohmytradeagent.contract.WatchlistTriggerPayload;
import io.temporal.activity.ActivityInterface;

/**
 * Arm-time decision hook for a watchlist trigger entry: decides whether to arm the trigger and with
 * what size multiplier. {@link ArmContext} is the minimal shared context; it is additively
 * versionable so future AI inputs can be threaded in without changing this signature.
 */
@ActivityInterface
public interface WatchlistEntryDecider {

  ArmDecision evaluateWatchlistEntry(WatchlistTriggerPayload entry, ArmContext ctx);
}
