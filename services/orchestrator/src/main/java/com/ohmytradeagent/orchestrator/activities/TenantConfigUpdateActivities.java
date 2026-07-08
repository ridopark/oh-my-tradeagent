package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.TenantConfigUpdateRequest;
import com.ohmytradeagent.contract.TenantConfigUpdateResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * account-loss-cap-db (Phase 3) — DARK capability. Drives the tenant tighten-only account-cap write
 * through {@link com.ohmytradeagent.orchestrator.platform.TenantConfigWriter#update} and returns a
 * coarse {@link TenantConfigUpdateResult} outcome.
 *
 * <p>The impl is the SOLE place each {@code TenantConfigWriter} exception is caught and coarsened
 * into the result {@code outcome} enum (writer exception messages never reach the client). Only a
 * genuinely transient/unknown fault (an {@code IllegalStateException}) is left to propagate as a
 * retryable Activity failure, so the caller degrades to a 503 (write disposition unknown) rather
 * than a misleading success. Registered on the orchestrator-core worker.
 */
@ActivityInterface
public interface TenantConfigUpdateActivities {

  // Explicit activity type name — WITHOUT this it defaults to the capitalized method name "Update",
  // which COLLIDES with StrategyConfigUpdateActivities.update() (also "Update") on the shared
  // orchestrator-core worker → TypeAlreadyRegisteredException at boot (the whole orchestrator fails
  // to start). Both this workflow type and this activity name are net-new, so an explicit distinct
  // name is replay-safe. Do NOT rename the strategy one — that would break its command history.
  @ActivityMethod(name = "TenantConfigUpdate")
  TenantConfigUpdateResult update(TenantConfigUpdateRequest request);
}
