package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.WatchlistMirrorPayload;
import io.temporal.activity.ActivityInterface;
import java.util.List;

/**
 * Parses a verbatim watchlist payload into per-leg trigger entries for the fan-out parent. Lives in
 * an Activity (not the parent workflow body) so the regex parse + free-text strike validation never
 * touch the deterministic workflow path. The returned {@link WatchlistTriggerLeg} carries either a
 * well-formed payload or a skip reason (malformed strike/right) so the parent can audit-and-skip
 * that leg at arm time while still arming the rest.
 */
@ActivityInterface
public interface WatchlistTriggerActivities {

  List<WatchlistTriggerLeg> parseWatchlistTriggers(WatchlistMirrorPayload payload);
}
