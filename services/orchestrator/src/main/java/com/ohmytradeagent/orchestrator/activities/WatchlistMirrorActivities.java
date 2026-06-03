package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.WatchlistMirrorPayload;
import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface WatchlistMirrorActivities {

  void postWatchlistAlert(WatchlistMirrorPayload payload);
}
