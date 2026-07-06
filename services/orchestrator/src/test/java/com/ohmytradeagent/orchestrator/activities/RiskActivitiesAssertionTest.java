package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.activities.PreTradeCheckActivity;
import io.temporal.client.WorkflowClient;
import io.temporal.failure.ApplicationFailure;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Tests for {@link RiskActivitiesImpl#assertPreTradeCheckRoutable(StrategyConfig)}. */
class RiskActivitiesAssertionTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-05-13T17:22:31Z"), ZoneOffset.UTC);

  @Test
  void assertPreTradeCheckRoutable_throwsNonRetryable_whenEnabledAndPermissiveDefaultBeanWired() {
    PreTradeCheckActivity permissive = RiskCollaboratorDefaults.permissivePreTradeCheck();
    assertThat(permissive).isInstanceOf(PermissiveDefaultPreTradeCheck.class);
    RiskActivitiesImpl risk = buildRiskWith(permissive);
    StrategyConfig config = config();
    config.setPreTradeCheckEnabled(true);

    assertThatThrownBy(() -> risk.assertPreTradeCheckRoutable(config))
        .isInstanceOf(ApplicationFailure.class)
        .satisfies(
            t ->
                assertThat(((ApplicationFailure) t).getType())
                    .isEqualTo("PreTradeCheckMisconfigured"))
        .satisfies(t -> assertThat(((ApplicationFailure) t).isNonRetryable()).isTrue())
        .hasMessageContaining("dev")
        .hasMessageContaining("copytrade-v1");
  }

  @Test
  void assertPreTradeCheckRoutable_returnsNormally_whenEnabledAndNonPermissiveBeanWired() {
    PreTradeCheckActivity mockBean = mock(PreTradeCheckActivity.class);
    assertThat(mockBean).isNotInstanceOf(PermissiveDefaultPreTradeCheck.class);

    RiskActivitiesImpl risk = buildRiskWith(mockBean);
    StrategyConfig config = config();
    config.setPreTradeCheckEnabled(true);

    assertThatCode(() -> risk.assertPreTradeCheckRoutable(config)).doesNotThrowAnyException();
  }

  @Test
  void assertPreTradeCheckRoutable_returnsNormally_whenEnabledAndRoutableMarkerWired() {
    // Reproduction linkage: the 2026-07-06 incident was the throw at RiskActivitiesImpl:608 when
    // the
    // DB flag was on but only the permissive default was wired. Wiring the non-permissive routable
    // marker makes the guard pass so the check dispatches to exec.
    PreTradeCheckActivity routable = new RoutablePreTradeCheckActivity();
    assertThat(routable).isNotInstanceOf(PermissiveDefaultPreTradeCheck.class);

    RiskActivitiesImpl risk = buildRiskWith(routable);
    StrategyConfig config = config();
    config.setPreTradeCheckEnabled(true);

    assertThatCode(() -> risk.assertPreTradeCheckRoutable(config)).doesNotThrowAnyException();
  }

  @Test
  void assertPreTradeCheckRoutable_returnsNormally_whenDisabled() {
    RiskActivitiesImpl risk = buildRiskWith(RiskCollaboratorDefaults.permissivePreTradeCheck());

    StrategyConfig configFalse = config();
    configFalse.setPreTradeCheckEnabled(false);
    assertThatCode(() -> risk.assertPreTradeCheckRoutable(configFalse)).doesNotThrowAnyException();

    StrategyConfig configNull = config();
    configNull.setPreTradeCheckEnabled(null);
    assertThatCode(() -> risk.assertPreTradeCheckRoutable(configNull)).doesNotThrowAnyException();
  }

  private static RiskActivitiesImpl buildRiskWith(PreTradeCheckActivity preTradeCheckActivity) {
    return new RiskActivitiesImpl(
        (tenant, strategy) -> 0L,
        CLOCK,
        mock(WorkflowClient.class),
        RiskCollaboratorDefaults.permissivePortfolioSnapshot(),
        SectorResolver.CONFIG_BACKED,
        RiskCollaboratorDefaults.zeroDailyTradeCounter(),
        RiskCollaboratorDefaults.zeroDrawdownSampler(),
        preTradeCheckActivity);
  }

  private static StrategyConfig config() {
    StrategyConfig c = new StrategyConfig();
    c.setSchemaVersion(1L);
    c.setTenantId("dev");
    c.setStrategyId("copytrade-v1");
    return c;
  }
}
