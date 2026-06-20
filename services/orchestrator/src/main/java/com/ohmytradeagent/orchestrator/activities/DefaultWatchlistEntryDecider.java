package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.ArmContext;
import com.ohmytradeagent.contract.ArmDecision;
import com.ohmytradeagent.contract.WatchlistTriggerPayload;
import java.math.BigDecimal;

/**
 * Pass-through default that always arms at the unmodified size. Plain POJO; the worker registration
 * (Phase 6) wires it in {@code TemporalWorkerConfig}.
 */
public class DefaultWatchlistEntryDecider implements WatchlistEntryDecider {

  @Override
  public ArmDecision evaluateWatchlistEntry(WatchlistTriggerPayload entry, ArmContext ctx) {
    return new ArmDecision()
        .withArm(true)
        .withSizeMultiplier(BigDecimal.ONE)
        .withReason("default-pass-through");
  }
}
