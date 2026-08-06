package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.StrategyConfigCreateRequest;
import com.ohmytradeagent.contract.StrategyConfigCreateResult;
import com.ohmytradeagent.orchestrator.platform.InvalidConfigException;
import com.ohmytradeagent.orchestrator.platform.RowAlreadyExistsException;
import com.ohmytradeagent.orchestrator.platform.StrategyConfigWriter;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Phase I-1b: the create activity impl is the SOLE place {@link StrategyConfigWriter#create}
 * exceptions are coarsened into the result outcome enum. Pins each exception→outcome mapping, the
 * CREATED+created_version success path, schema_version=1, and the fail-open property: an {@link
 * IllegalStateException} is NOT caught (it propagates as a retryable Activity failure → caller
 * 503), so it is NEVER reported as success.
 */
class StrategyConfigCreateActivitiesImplTest {

  private static final String TENANT = "acme";
  private static final String STRATEGY = "copytrade-v1";
  private static final String OPERATOR = "ridopark@gmail.com";

  private static StrategyConfigCreateRequest request() {
    StrategyConfigCreateRequest req = new StrategyConfigCreateRequest();
    req.setSchemaVersion(1L);
    req.setTenantId(TENANT);
    req.setStrategyId(STRATEGY);
    req.setConfig(new StrategyConfig());
    req.setOperatorId(OPERATOR);
    req.setCorrelationId("corr-1");
    return req;
  }

  @Test
  void create_onWriterSuccess_returnsCreatedWithVersion1() {
    StrategyConfigWriter writer = mock(StrategyConfigWriter.class);
    when(writer.create(eq(TENANT), eq(STRATEGY), any(StrategyConfig.class), any(), eq(OPERATOR)))
        .thenReturn(1L);

    StrategyConfigCreateResult result =
        new StrategyConfigCreateActivitiesImpl(writer).create(request());

    assertThat(result.getSchemaVersion()).isEqualTo(1L);
    assertThat(result.getOutcome()).isEqualTo(StrategyConfigCreateResult.Outcome.CREATED);
    assertThat(result.getCreatedVersion()).isEqualTo(1L);
  }

  @Test
  void create_threadsAccountDailyLossPct_fromRequestToWriter() {
    // PLAN-2026-08-05: the operator-supplied account_daily_loss_pct on the request must reach
    // writer.create's new parameter so a LIVE create can arm the tenant's account cap.
    StrategyConfigWriter writer = mock(StrategyConfigWriter.class);
    when(writer.create(
            anyString(),
            anyString(),
            any(StrategyConfig.class),
            any(BigDecimal.class),
            anyString()))
        .thenReturn(1L);

    StrategyConfigCreateRequest req = request();
    req.setAccountDailyLossPct(new BigDecimal("0.20"));

    new StrategyConfigCreateActivitiesImpl(writer).create(req);

    org.mockito.ArgumentCaptor<BigDecimal> pct =
        org.mockito.ArgumentCaptor.forClass(BigDecimal.class);
    verify(writer)
        .create(eq(TENANT), eq(STRATEGY), any(StrategyConfig.class), pct.capture(), eq(OPERATOR));
    assertThat(pct.getValue()).isEqualByComparingTo("0.20");
  }

  @Test
  void create_onRowAlreadyExists_mapsToAlreadyExists() {
    assertOutcome(
        new RowAlreadyExistsException("exists"), StrategyConfigCreateResult.Outcome.ALREADY_EXISTS);
  }

  @Test
  void create_onInvalidConfig_mapsToInvalid() {
    assertOutcome(
        new InvalidConfigException("malformed"),
        StrategyConfigCreateResult.Outcome.REJECTED_INVALID);
  }

  @Test
  void create_onIllegalState_propagates_notCoarsened() {
    StrategyConfigWriter writer = mock(StrategyConfigWriter.class);
    when(writer.create(anyString(), anyString(), any(StrategyConfig.class), any(), anyString()))
        .thenThrow(new IllegalStateException("serialization fault"));

    StrategyConfigCreateActivitiesImpl activity = new StrategyConfigCreateActivitiesImpl(writer);

    assertThatThrownBy(() -> activity.create(request())).isInstanceOf(IllegalStateException.class);
  }

  private static void assertOutcome(
      RuntimeException thrown, StrategyConfigCreateResult.Outcome expected) {
    StrategyConfigWriter writer = mock(StrategyConfigWriter.class);
    when(writer.create(anyString(), anyString(), any(StrategyConfig.class), any(), anyString()))
        .thenThrow(thrown);

    StrategyConfigCreateResult result =
        new StrategyConfigCreateActivitiesImpl(writer).create(request());

    assertThat(result.getSchemaVersion()).isEqualTo(1L);
    assertThat(result.getOutcome()).isEqualTo(expected);
    assertThat(result.getCreatedVersion()).isNull();
  }
}
