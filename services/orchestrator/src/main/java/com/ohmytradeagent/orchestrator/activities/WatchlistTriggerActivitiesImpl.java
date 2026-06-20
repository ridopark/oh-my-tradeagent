package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.WatchlistMirrorPayload;
import com.ohmytradeagent.orchestrator.activities.WatchlistParser.ParseResult;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Parses a watchlist payload into per-leg trigger entries (see {@link WatchlistTriggerActivities}).
 * Reuses the exact same {@link WatchlistParser} the Discord-mirror activity uses, then maps each
 * present call/put leg via {@link WatchlistTriggerRowMapper}. A not-clean parse yields an empty
 * list (the mirror's raw-fallback path is the parent's concern; the fan-out never arms a partial
 * table).
 */
@Component
public class WatchlistTriggerActivitiesImpl implements WatchlistTriggerActivities {

  @Override
  public List<WatchlistTriggerLeg> parseWatchlistTriggers(WatchlistMirrorPayload payload) {
    ParseResult parsed = WatchlistParser.parse(payload.getRawText());
    if (!parsed.clean() || parsed.rows().isEmpty()) {
      return List.of();
    }
    return WatchlistTriggerRowMapper.map(payload, parsed);
  }
}
