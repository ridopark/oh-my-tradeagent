package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.StrategyConfigUpdateRequest;
import com.ohmytradeagent.contract.StrategyConfigUpdateResult;
import io.temporal.activity.ActivityInterface;

/**
 * UI-P3-b (strategy-config-write) — DARK capability. Drives the reduce-or-hold-risk runtime config
 * write through {@link com.ohmytradeagent.orchestrator.platform.StrategyConfigWriter#update} and
 * returns a coarse {@link StrategyConfigUpdateResult} outcome.
 *
 * <p>The impl is the SOLE place each {@code StrategyConfigWriter} exception is caught and coarsened
 * into the result {@code outcome} enum (mirroring the UI-P2 no-internals rule — writer exception
 * messages never reach the client). Only a genuinely transient/unknown fault (an {@code
 * IllegalStateException} from a corrupt stored row) is left to propagate as a retryable Activity
 * failure, so the caller degrades to a 503 (write disposition unknown) rather than a misleading
 * success. Registered on the orchestrator-core worker.
 */
@ActivityInterface
public interface StrategyConfigUpdateActivities {

  StrategyConfigUpdateResult update(StrategyConfigUpdateRequest request);
}
