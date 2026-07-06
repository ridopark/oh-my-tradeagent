package com.ohmytradeagent.orchestrator.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.ohmytradeagent.contract.activities.PreTradeCheckActivity;
import com.ohmytradeagent.orchestrator.activities.PermissiveDefaultPreTradeCheck;
import com.ohmytradeagent.orchestrator.activities.RoutablePreTradeCheckActivity;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Verifies the {@code @ConditionalOnProperty} / {@code @ConditionalOnMissingBean} interplay for the
 * {@link com.ohmytradeagent.contract.activities.PreTradeCheckActivity} bean: property on → the
 * non-permissive {@link RoutablePreTradeCheckActivity} marker is injected (permissive default backs
 * off); property absent/false → the permissive default is injected (guard stays fail-closed).
 */
class RiskCollaboratorsConfigPreTradeCheckWiringTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withBean(WorkflowClient.class, () -> mock(WorkflowClient.class))
          .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
          .withUserConfiguration(RiskCollaboratorsConfig.class);

  @Test
  void routableMarkerWired_whenPropertyTrue() {
    runner
        .withPropertyValues("orchestrator.pre-trade-check.routing-enabled=true")
        .run(
            ctx -> {
              PreTradeCheckActivity bean = ctx.getBean(PreTradeCheckActivity.class);
              assertThat(bean).isInstanceOf(RoutablePreTradeCheckActivity.class);
              assertThat(bean).isNotInstanceOf(PermissiveDefaultPreTradeCheck.class);
            });
  }

  @Test
  void permissiveDefaultWired_whenPropertyAbsent() {
    runner.run(
        ctx -> {
          PreTradeCheckActivity bean = ctx.getBean(PreTradeCheckActivity.class);
          assertThat(bean).isInstanceOf(PermissiveDefaultPreTradeCheck.class);
          assertThat(bean).isNotInstanceOf(RoutablePreTradeCheckActivity.class);
        });
  }

  @Test
  void permissiveDefaultWired_whenPropertyFalse() {
    runner
        .withPropertyValues("orchestrator.pre-trade-check.routing-enabled=false")
        .run(
            ctx -> {
              PreTradeCheckActivity bean = ctx.getBean(PreTradeCheckActivity.class);
              assertThat(bean).isInstanceOf(PermissiveDefaultPreTradeCheck.class);
            });
  }
}
