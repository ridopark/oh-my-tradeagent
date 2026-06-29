package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.StrategyConfigCreateRequest;
import com.ohmytradeagent.contract.StrategyConfigCreateResult;
import io.temporal.activity.ActivityInterface;

/**
 * Phase I-1b (operator-account-onboarding) create-tenant — DARK capability. Drives the
 * create-tenant INSERT through {@link
 * com.ohmytradeagent.orchestrator.platform.StrategyConfigWriter#create} and returns a coarse {@link
 * StrategyConfigCreateResult} outcome.
 *
 * <p>The impl is the SOLE place each {@code StrategyConfigWriter} exception is caught and coarsened
 * into the result {@code outcome} enum (writer exception messages never reach the client). Only a
 * genuinely transient/unknown fault (an {@code IllegalStateException}) is left to propagate as a
 * retryable Activity failure, so the caller degrades to a 503 (create disposition unknown) rather
 * than a misleading success. Registered on the orchestrator-core worker.
 */
@ActivityInterface
public interface StrategyConfigCreateActivities {

  StrategyConfigCreateResult create(StrategyConfigCreateRequest request);
}
