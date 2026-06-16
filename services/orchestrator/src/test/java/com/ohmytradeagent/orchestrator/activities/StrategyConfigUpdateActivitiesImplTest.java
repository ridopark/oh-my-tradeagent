package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.StrategyConfigUpdateRequest;
import com.ohmytradeagent.contract.StrategyConfigUpdateResult;
import com.ohmytradeagent.orchestrator.platform.DangerousFieldChangeRejected;
import com.ohmytradeagent.orchestrator.platform.InvalidConfigException;
import com.ohmytradeagent.orchestrator.platform.OptimisticLockException;
import com.ohmytradeagent.orchestrator.platform.StrategyConfigWriter;
import com.ohmytradeagent.orchestrator.platform.YamlStrategyRegistry;
import org.junit.jupiter.api.Test;

/**
 * UI-P3-b: the activity impl is the SOLE place {@link StrategyConfigWriter} exceptions are
 * coarsened into the result outcome enum. Pins each exception→outcome mapping, the
 * UPDATED+new_version success path, schema_version=1, and the fail-open property: a corrupt-row
 * {@link IllegalStateException} is NOT caught (it propagates as a retryable Activity failure →
 * caller 503), so it is NEVER reported as success.
 */
class StrategyConfigUpdateActivitiesImplTest {

  private static final String TENANT = "acme";
  private static final String STRATEGY = "copytrade-v1";
  private static final String ACTOR = "api-gateway:/strategy-config";

  private static StrategyConfigUpdateRequest request() {
    StrategyConfigUpdateRequest req = new StrategyConfigUpdateRequest();
    req.setSchemaVersion(1L);
    req.setTenantId(TENANT);
    req.setStrategyId(STRATEGY);
    req.setConfig(new StrategyConfig());
    req.setExpectedVersion(3L);
    req.setActor(ACTOR);
    req.setCorrelationId("corr-1");
    return req;
  }

  @Test
  void update_onWriterSuccess_returnsUpdatedWithNewVersion() {
    StrategyConfigWriter writer = mock(StrategyConfigWriter.class);
    when(writer.update(eq(TENANT), eq(STRATEGY), any(StrategyConfig.class), eq(3L), eq(ACTOR)))
        .thenReturn(4L);

    StrategyConfigUpdateResult result =
        new StrategyConfigUpdateActivitiesImpl(writer).update(request());

    assertThat(result.getSchemaVersion()).isEqualTo(1L);
    assertThat(result.getOutcome()).isEqualTo(StrategyConfigUpdateResult.Outcome.UPDATED);
    assertThat(result.getNewVersion()).isEqualTo(4L);
  }

  @Test
  void update_onStrategyNotFound_mapsToNotFound() {
    assertOutcome(
        new YamlStrategyRegistry.StrategyNotFoundException("no row"),
        StrategyConfigUpdateResult.Outcome.NOT_FOUND);
  }

  @Test
  void update_onOptimisticLock_mapsToStaleVersion() {
    assertOutcome(
        new OptimisticLockException("stale"),
        StrategyConfigUpdateResult.Outcome.REJECTED_STALE_VERSION);
  }

  @Test
  void update_onDangerousFieldChange_mapsToDangerous_neverUpdated() {
    assertOutcome(
        new DangerousFieldChangeRejected("widened a cap"),
        StrategyConfigUpdateResult.Outcome.REJECTED_DANGEROUS);
  }

  @Test
  void update_onInvalidConfig_mapsToInvalid() {
    assertOutcome(
        new InvalidConfigException("malformed"),
        StrategyConfigUpdateResult.Outcome.REJECTED_INVALID);
  }

  @Test
  void update_onCorruptStoredRow_illegalStateException_propagates_notCoarsened() {
    StrategyConfigWriter writer = mock(StrategyConfigWriter.class);
    when(writer.update(anyString(), anyString(), any(StrategyConfig.class), anyLong(), anyString()))
        .thenThrow(new IllegalStateException("corrupt stored strategy_config row"));

    StrategyConfigUpdateActivitiesImpl activity = new StrategyConfigUpdateActivitiesImpl(writer);

    assertThatThrownBy(() -> activity.update(request())).isInstanceOf(IllegalStateException.class);
  }

  private static void assertOutcome(
      RuntimeException thrown, StrategyConfigUpdateResult.Outcome expected) {
    StrategyConfigWriter writer = mock(StrategyConfigWriter.class);
    when(writer.update(anyString(), anyString(), any(StrategyConfig.class), anyLong(), anyString()))
        .thenThrow(thrown);

    StrategyConfigUpdateResult result =
        new StrategyConfigUpdateActivitiesImpl(writer).update(request());

    assertThat(result.getSchemaVersion()).isEqualTo(1L);
    assertThat(result.getOutcome()).isEqualTo(expected);
    assertThat(result.getNewVersion()).isNull();
  }
}
