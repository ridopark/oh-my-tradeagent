package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.CopytradeSignalPayload;
import com.ohmytradeagent.contract.KillSwitchState;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.domain.RejectionReason;
import com.ohmytradeagent.orchestrator.domain.RiskDecision;
import com.ohmytradeagent.orchestrator.workflows.KillSwitchWorkflow;
import io.temporal.client.WorkflowClient;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;

/**
 * Risk gate Activity. Phase 5 wires the kill-switch read via {@code
 * KillSwitchWorkflow.killswitchState()} — any failure (workflow-not-found, query rejection, service
 * timeout) fails CLOSED with {@link RejectionReason#KILL_SWITCH_UNAVAILABLE}. Tripped or
 * within-cooldown state rejects with the corresponding reason.
 */
@Component
public class RiskActivitiesImpl implements RiskActivities {

  static final Duration FUTURE_DATE_TOLERANCE = Duration.ofSeconds(5);

  private final PositionCounter positionCounter;
  private final Clock clock;
  private final WorkflowClient workflowClient;

  public RiskActivitiesImpl(
      PositionCounter positionCounter, Clock clock, WorkflowClient workflowClient) {
    this.positionCounter = positionCounter;
    this.clock = clock;
    this.workflowClient = workflowClient;
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

    RiskDecision killSwitchDecision = checkKillSwitch(payload, now);
    if (killSwitchDecision != null) {
      return killSwitchDecision;
    }

    long openPositions = positionCounter.countOpen(payload.getTenantId(), payload.getStrategyId());
    if (openPositions >= config.getMaxPositions()) {
      return RiskDecision.rejected(RejectionReason.MAX_POSITIONS_EXCEEDED, "open=" + openPositions);
    }

    return RiskDecision.approved();
  }

  /**
   * Reads the kill-switch state and returns a rejection if tripped or within cool-down. Any
   * exception from the query path is treated as fail-closed with KILL_SWITCH_UNAVAILABLE — this
   * covers WorkflowNotFoundException, WorkflowQueryException, WorkflowQueryRejectedException,
   * WorkflowServiceException, and any TimeoutException wrapped in a RuntimeException.
   */
  private RiskDecision checkKillSwitch(CopytradeSignalPayload payload, OffsetDateTime now) {
    if (workflowClient == null) {
      // Defensive: production env always wires WorkflowClient; fail closed if it is somehow null.
      return RiskDecision.rejected(RejectionReason.KILL_SWITCH_UNAVAILABLE, "no_client");
    }
    KillSwitchState state;
    try {
      String wfId = WorkflowIds.killswitch(payload.getTenantId(), payload.getStrategyId());
      KillSwitchWorkflow stub = workflowClient.newWorkflowStub(KillSwitchWorkflow.class, wfId);
      state = stub.killswitchState();
    } catch (Exception e) {
      return RiskDecision.rejected(
          RejectionReason.KILL_SWITCH_UNAVAILABLE, e.getClass().getSimpleName());
    }
    if (state == null) {
      return RiskDecision.rejected(RejectionReason.KILL_SWITCH_UNAVAILABLE, "null_state");
    }
    if (Boolean.TRUE.equals(state.getTripped())) {
      String detail = state.getReason() != null ? "reason=" + state.getReason() : null;
      return RiskDecision.rejected(RejectionReason.KILL_SWITCH_TRIPPED, detail);
    }
    OffsetDateTime cd = state.getCoolingDownUntil();
    if (cd != null && now.isBefore(cd)) {
      return RiskDecision.rejected(
          RejectionReason.KILL_SWITCH_COOLING_DOWN, "until=" + cd.toString());
    }
    return null;
  }
}
