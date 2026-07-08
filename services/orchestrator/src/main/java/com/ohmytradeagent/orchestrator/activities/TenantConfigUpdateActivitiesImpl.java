package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.TenantConfigUpdateRequest;
import com.ohmytradeagent.contract.TenantConfigUpdateResult;
import com.ohmytradeagent.orchestrator.platform.BelowFloorRejected;
import com.ohmytradeagent.orchestrator.platform.DangerousFieldChangeRejected;
import com.ohmytradeagent.orchestrator.platform.InvalidConfigException;
import com.ohmytradeagent.orchestrator.platform.OptimisticLockException;
import com.ohmytradeagent.orchestrator.platform.TenantConfigNotFoundException;
import com.ohmytradeagent.orchestrator.platform.TenantConfigWriter;
import org.springframework.stereotype.Component;

/**
 * account-loss-cap-db (Phase 3) impl. The ONLY place {@link TenantConfigWriter} exceptions are
 * coarsened into the result {@code outcome} enum — writer exception messages never surface to the
 * client. The {@code UPDATED} outcome is constructed EXCLUSIVELY on the writer's normal {@code
 * long} return; a {@link DangerousFieldChangeRejected} / {@link BelowFloorRejected} is NEVER turned
 * into {@code UPDATED} (the tighten-only property).
 *
 * <p>Any unexpected {@link IllegalStateException} is deliberately NOT caught — it propagates as a
 * retryable Activity failure so the api-gateway caller degrades to a 503 (write disposition
 * unknown) rather than a misleading success or a swallowed fault.
 */
@Component
public class TenantConfigUpdateActivitiesImpl implements TenantConfigUpdateActivities {

  private final TenantConfigWriter writer;

  public TenantConfigUpdateActivitiesImpl(TenantConfigWriter writer) {
    this.writer = writer;
  }

  @Override
  public TenantConfigUpdateResult update(TenantConfigUpdateRequest request) {
    TenantConfigUpdateResult result = new TenantConfigUpdateResult();
    result.setSchemaVersion(1L);
    try {
      long newVersion =
          writer.update(
              request.getTenantId(),
              request.getAccountDailyLossThreshold(),
              request.getAccountDailyLossPct(),
              request.getExpectedVersion(),
              request.getActor());
      // UPDATED is constructed ONLY here, on the writer's normal long return.
      result.setOutcome(TenantConfigUpdateResult.Outcome.UPDATED);
      result.setNewVersion(newVersion);
    } catch (TenantConfigNotFoundException e) {
      result.setOutcome(TenantConfigUpdateResult.Outcome.NOT_FOUND);
    } catch (OptimisticLockException e) {
      result.setOutcome(TenantConfigUpdateResult.Outcome.REJECTED_STALE_VERSION);
    } catch (DangerousFieldChangeRejected e) {
      result.setOutcome(TenantConfigUpdateResult.Outcome.REJECTED_TIGHTEN_ONLY);
    } catch (BelowFloorRejected e) {
      result.setOutcome(TenantConfigUpdateResult.Outcome.REJECTED_BELOW_FLOOR);
    } catch (InvalidConfigException e) {
      result.setOutcome(TenantConfigUpdateResult.Outcome.REJECTED_INVALID);
    }
    // IllegalStateException (unexpected fault) is NOT caught — it propagates as a retryable
    // Activity
    // failure, surfacing to the caller as 503 (write disposition unknown).
    return result;
  }
}
