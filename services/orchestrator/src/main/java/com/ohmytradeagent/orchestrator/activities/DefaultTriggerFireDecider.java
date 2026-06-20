package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.ArmContext;
import com.ohmytradeagent.contract.FireDecision;
import com.ohmytradeagent.contract.WatchlistTriggerPayload;
import java.math.BigDecimal;

/**
 * Pass-through default that always proceeds at the unmodified size. Plain POJO; the worker
 * registration (Phase 6) wires it in {@code TemporalWorkerConfig}.
 */
public class DefaultTriggerFireDecider implements TriggerFireDecider {

  @Override
  public FireDecision evaluateTriggerFire(WatchlistTriggerPayload entry, ArmContext ctx) {
    return new FireDecision()
        .withProceed(true)
        .withSizeMultiplier(BigDecimal.ONE)
        .withReason("default-pass-through");
  }
}
