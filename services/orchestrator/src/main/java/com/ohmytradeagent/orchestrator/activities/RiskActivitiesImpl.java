package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.CopytradeSignalPayload;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.domain.RejectionReason;
import com.ohmytradeagent.orchestrator.domain.RiskDecision;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;

/**
 * Risk gate Activity. Phase 2a wires four checks in order: author whitelist, signal timestamp
 * sanity (future-dated rejected), signal age, max-open-positions count. Kill-switch read is a stub
 * (returns tripped=false) per plan line 451 — fail-closed semantics land in Phase 5 with the real
 * KillSwitchWorkflow.
 */
@Component
public class RiskActivitiesImpl implements RiskActivities {

  static final Duration FUTURE_DATE_TOLERANCE = Duration.ofSeconds(5);

  private final PositionCounter positionCounter;
  private final Clock clock;

  public RiskActivitiesImpl(PositionCounter positionCounter, Clock clock) {
    this.positionCounter = positionCounter;
    this.clock = clock;
  }

  @Override
  public RiskDecision checkEntry(CopytradeSignalPayload payload, StrategyConfig config) {
    if (!config.getAuthorWhitelist().contains(payload.getAuthor())) {
      return RiskDecision.rejected(
          RejectionReason.AUTHOR_NOT_WHITELISTED, "author=" + payload.getAuthor());
    }

    OffsetDateTime now = OffsetDateTime.now(clock);
    OffsetDateTime postedAt = payload.getPostedAt();
    if (postedAt.isAfter(now.plus(FUTURE_DATE_TOLERANCE))) {
      Duration skew = Duration.between(now, postedAt);
      return RiskDecision.rejected(
          RejectionReason.INVALID_TIMESTAMP, "future_skew_secs=" + skew.toSeconds());
    }

    long ageSecs = Duration.between(postedAt, now).getSeconds();
    if (ageSecs > config.getMaxSignalAgeSecs()) {
      return RiskDecision.rejected(RejectionReason.SIGNAL_TOO_OLD, "age_secs=" + ageSecs);
    }

    // Phase 2a: killswitch read stub returns false. Phase 5 wires KillSwitchWorkflow.query +
    // workflow-not-found → fail-closed (KILL_SWITCH_UNAVAILABLE).
    boolean killSwitchTripped = false;
    if (killSwitchTripped) {
      return RiskDecision.rejected(RejectionReason.KILL_SWITCH_TRIPPED, null);
    }

    long openPositions = positionCounter.countOpen(payload.getTenantId(), payload.getStrategyId());
    if (openPositions >= config.getMaxPositions()) {
      return RiskDecision.rejected(RejectionReason.MAX_POSITIONS_EXCEEDED, "open=" + openPositions);
    }

    return RiskDecision.approved();
  }
}
