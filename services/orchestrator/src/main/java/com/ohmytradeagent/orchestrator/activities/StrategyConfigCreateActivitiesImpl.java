package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.StrategyConfigCreateRequest;
import com.ohmytradeagent.contract.StrategyConfigCreateResult;
import com.ohmytradeagent.orchestrator.platform.InvalidConfigException;
import com.ohmytradeagent.orchestrator.platform.RowAlreadyExistsException;
import com.ohmytradeagent.orchestrator.platform.StrategyConfigWriter;
import org.springframework.stereotype.Component;

/**
 * Phase I-1b create-tenant impl. The ONLY place {@link StrategyConfigWriter#create} exceptions are
 * coarsened into the result {@code outcome} enum — writer exception messages never surface to the
 * client. The {@code CREATED} outcome is constructed EXCLUSIVELY on the writer's normal {@code
 * long} return.
 *
 * <p>{@link IllegalStateException} (e.g. a serialization fault) is deliberately NOT caught — it
 * propagates as a retryable Activity failure so the api-gateway caller degrades to a 503 (create
 * disposition unknown) rather than a misleading success or a swallowed fault.
 */
@Component
public class StrategyConfigCreateActivitiesImpl implements StrategyConfigCreateActivities {

  private final StrategyConfigWriter writer;

  public StrategyConfigCreateActivitiesImpl(StrategyConfigWriter writer) {
    this.writer = writer;
  }

  @Override
  public StrategyConfigCreateResult create(StrategyConfigCreateRequest request) {
    StrategyConfigCreateResult result = new StrategyConfigCreateResult();
    result.setSchemaVersion(1L);
    try {
      long createdVersion =
          writer.create(
              request.getTenantId(),
              request.getStrategyId(),
              request.getConfig(),
              request.getOperatorId());
      // CREATED is constructed ONLY here, on the writer's normal long return.
      result.setOutcome(StrategyConfigCreateResult.Outcome.CREATED);
      result.setCreatedVersion(createdVersion);
    } catch (RowAlreadyExistsException e) {
      result.setOutcome(StrategyConfigCreateResult.Outcome.ALREADY_EXISTS);
    } catch (InvalidConfigException e) {
      result.setOutcome(StrategyConfigCreateResult.Outcome.REJECTED_INVALID);
    }
    // IllegalStateException (serialization fault) is NOT caught — it propagates as a retryable
    // Activity failure, surfacing to the caller as 503 (create disposition unknown).
    return result;
  }
}
