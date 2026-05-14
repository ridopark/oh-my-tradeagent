package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.CopytradeSignalPayload;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.domain.RiskDecision;
import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface RiskActivities {

  RiskDecision checkEntry(CopytradeSignalPayload payload, StrategyConfig config);
}
