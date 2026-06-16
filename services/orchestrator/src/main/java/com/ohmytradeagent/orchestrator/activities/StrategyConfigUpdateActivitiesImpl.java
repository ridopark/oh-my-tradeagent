package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.StrategyConfigUpdateRequest;
import com.ohmytradeagent.contract.StrategyConfigUpdateResult;
import com.ohmytradeagent.orchestrator.platform.DangerousFieldChangeRejected;
import com.ohmytradeagent.orchestrator.platform.InvalidConfigException;
import com.ohmytradeagent.orchestrator.platform.OptimisticLockException;
import com.ohmytradeagent.orchestrator.platform.StrategyConfigWriter;
import com.ohmytradeagent.orchestrator.platform.YamlStrategyRegistry;
import org.springframework.stereotype.Component;

/**
 * UI-P3-b impl. The ONLY place {@link StrategyConfigWriter} exceptions are coarsened into the
 * result {@code outcome} enum — writer exception messages never surface to the client (UI-P2
 * no-internals rule). The {@code UPDATED} outcome is constructed EXCLUSIVELY on the writer's normal
 * {@code long} return; a {@link DangerousFieldChangeRejected} is NEVER turned into {@code UPDATED}
 * (the reduce-or-hold-risk property).
 *
 * <p>{@link IllegalStateException} (a corrupt stored row the writer cannot deserialize) is
 * deliberately NOT caught — it propagates as a retryable Activity failure so the api-gateway caller
 * degrades to a 503 (write disposition unknown) rather than a misleading success or a swallowed
 * fault.
 */
@Component
public class StrategyConfigUpdateActivitiesImpl implements StrategyConfigUpdateActivities {

  private final StrategyConfigWriter writer;

  public StrategyConfigUpdateActivitiesImpl(StrategyConfigWriter writer) {
    this.writer = writer;
  }

  @Override
  public StrategyConfigUpdateResult update(StrategyConfigUpdateRequest request) {
    StrategyConfigUpdateResult result = new StrategyConfigUpdateResult();
    result.setSchemaVersion(1L);
    try {
      long newVersion =
          writer.update(
              request.getTenantId(),
              request.getStrategyId(),
              request.getConfig(),
              request.getExpectedVersion(),
              request.getActor());
      // UPDATED is constructed ONLY here, on the writer's normal long return.
      result.setOutcome(StrategyConfigUpdateResult.Outcome.UPDATED);
      result.setNewVersion(newVersion);
    } catch (YamlStrategyRegistry.StrategyNotFoundException e) {
      result.setOutcome(StrategyConfigUpdateResult.Outcome.NOT_FOUND);
    } catch (OptimisticLockException e) {
      result.setOutcome(StrategyConfigUpdateResult.Outcome.REJECTED_STALE_VERSION);
    } catch (DangerousFieldChangeRejected e) {
      result.setOutcome(StrategyConfigUpdateResult.Outcome.REJECTED_DANGEROUS);
    } catch (InvalidConfigException e) {
      result.setOutcome(StrategyConfigUpdateResult.Outcome.REJECTED_INVALID);
    }
    // IllegalStateException (corrupt stored row) is NOT caught — it propagates as a retryable
    // Activity failure, surfacing to the caller as 503 (write disposition unknown).
    return result;
  }
}
