package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.TenantConfigUpdateRequest;
import com.ohmytradeagent.contract.TenantConfigUpdateResult;
import com.ohmytradeagent.orchestrator.platform.BelowFloorRejected;
import com.ohmytradeagent.orchestrator.platform.DangerousFieldChangeRejected;
import com.ohmytradeagent.orchestrator.platform.InvalidConfigException;
import com.ohmytradeagent.orchestrator.platform.OptimisticLockException;
import com.ohmytradeagent.orchestrator.platform.TenantConfigNotFoundException;
import com.ohmytradeagent.orchestrator.platform.TenantConfigWriter;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * account-loss-cap-db (Phase 3): the activity impl is the SOLE place {@link TenantConfigWriter}
 * exceptions are coarsened into the result outcome enum. Pins each exception→outcome mapping, the
 * UPDATED+new_version success path, schema_version=1, and the fail-open property: an unexpected
 * {@link IllegalStateException} is NOT caught (it propagates → caller 503), so it is NEVER reported
 * as success.
 */
class TenantConfigUpdateActivitiesImplTest {

  private static final String TENANT = "acme";
  private static final String ACTOR = "api-gateway:/tenant-config";

  private static TenantConfigUpdateRequest request() {
    TenantConfigUpdateRequest req = new TenantConfigUpdateRequest();
    req.setSchemaVersion(1L);
    req.setTenantId(TENANT);
    req.setAccountDailyLossThreshold(new BigDecimal("2000"));
    req.setAccountDailyLossPct(new BigDecimal("0.30"));
    req.setExpectedVersion(3L);
    req.setActor(ACTOR);
    req.setCorrelationId("corr-1");
    return req;
  }

  @Test
  void update_onWriterSuccess_returnsUpdatedWithNewVersion() {
    TenantConfigWriter writer = mock(TenantConfigWriter.class);
    when(writer.update(eq(TENANT), any(BigDecimal.class), any(BigDecimal.class), eq(3L), eq(ACTOR)))
        .thenReturn(4L);

    TenantConfigUpdateResult result =
        new TenantConfigUpdateActivitiesImpl(writer).update(request());

    assertThat(result.getSchemaVersion()).isEqualTo(1L);
    assertThat(result.getOutcome()).isEqualTo(TenantConfigUpdateResult.Outcome.UPDATED);
    assertThat(result.getNewVersion()).isEqualTo(4L);
  }

  @Test
  void update_onNotFound_mapsToNotFound() {
    assertOutcome(
        new TenantConfigNotFoundException("no row"), TenantConfigUpdateResult.Outcome.NOT_FOUND);
  }

  @Test
  void update_onOptimisticLock_mapsToStaleVersion() {
    assertOutcome(
        new OptimisticLockException("stale"),
        TenantConfigUpdateResult.Outcome.REJECTED_STALE_VERSION);
  }

  @Test
  void update_onTightenOnlyViolation_mapsToTightenOnly_neverUpdated() {
    assertOutcome(
        new DangerousFieldChangeRejected("raised a cap"),
        TenantConfigUpdateResult.Outcome.REJECTED_TIGHTEN_ONLY);
  }

  @Test
  void update_onBelowFloor_mapsToBelowFloor() {
    assertOutcome(
        new BelowFloorRejected("below floor"),
        TenantConfigUpdateResult.Outcome.REJECTED_BELOW_FLOOR);
  }

  @Test
  void update_onInvalid_mapsToInvalid() {
    assertOutcome(
        new InvalidConfigException("out of range"),
        TenantConfigUpdateResult.Outcome.REJECTED_INVALID);
  }

  @Test
  void update_onUnexpectedIllegalState_propagates_notCoarsened() {
    TenantConfigWriter writer = mock(TenantConfigWriter.class);
    when(writer.update(anyString(), any(), any(), anyLong(), anyString()))
        .thenThrow(new IllegalStateException("unexpected fault"));

    TenantConfigUpdateActivitiesImpl activity = new TenantConfigUpdateActivitiesImpl(writer);

    assertThatThrownBy(() -> activity.update(request())).isInstanceOf(IllegalStateException.class);
  }

  private static void assertOutcome(
      RuntimeException thrown, TenantConfigUpdateResult.Outcome expected) {
    TenantConfigWriter writer = mock(TenantConfigWriter.class);
    when(writer.update(anyString(), any(), any(), anyLong(), anyString())).thenThrow(thrown);

    TenantConfigUpdateResult result =
        new TenantConfigUpdateActivitiesImpl(writer).update(request());

    assertThat(result.getSchemaVersion()).isEqualTo(1L);
    assertThat(result.getOutcome()).isEqualTo(expected);
    assertThat(result.getNewVersion()).isNull();
  }
}
