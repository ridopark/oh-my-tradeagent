package com.ohmytradeagent.orchestrator.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.OrderUtils;

/**
 * P0c-b2 boot ordering: the {@link StrategyConfigSeedReconciler} must run strictly BEFORE both
 * config validators (so db-mode validators read a back-filled store), and both validators must run
 * strictly BEFORE the workflow-starting bootstrappers ({@link KillSwitchBootstrapper}, {@link
 * ReconciliationScheduleBootstrapper}) — an unsafe config never reaches the trading path.
 *
 * <p>Asserts the resolved {@code @Order} values directly (lower value = earlier; the Spring {@code
 * ApplicationRunner} contract orders by {@code @Order} / {@link Ordered}). The default-order
 * workers carry no {@code @Order} and resolve to {@link Ordered#LOWEST_PRECEDENCE}.
 */
class StrategyConfigBootstrapOrderingTest {

  private static int order(Class<?> type) {
    Integer resolved = OrderUtils.getOrder(type);
    return resolved == null ? Ordered.LOWEST_PRECEDENCE : resolved;
  }

  @Test
  void seederRunsBeforeBothValidators() {
    int seeder = order(StrategyConfigSeedReconciler.class);
    int liveGate = order(LiveRequiredGateBootstrapper.class);
    int crossTenant = order(CrossTenantBrokerTargetBootstrapper.class);

    assertThat(seeder).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    assertThat(seeder).isLessThan(liveGate);
    assertThat(seeder).isLessThan(crossTenant);
  }

  @Test
  void bothValidatorsShareTheSameOrderAfterTheSeeder() {
    assertThat(order(LiveRequiredGateBootstrapper.class))
        .isEqualTo(Ordered.HIGHEST_PRECEDENCE + 10)
        .isEqualTo(order(CrossTenantBrokerTargetBootstrapper.class));
  }

  @Test
  void validatorsRunBeforeDefaultOrderWorkers() {
    int liveGate = order(LiveRequiredGateBootstrapper.class);
    int crossTenant = order(CrossTenantBrokerTargetBootstrapper.class);
    int killSwitch = order(KillSwitchBootstrapper.class);
    int reconciliation = order(ReconciliationScheduleBootstrapper.class);

    // The workflow-starting bootstrappers keep their default (lowest) precedence.
    assertThat(killSwitch).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    assertThat(reconciliation).isEqualTo(Ordered.LOWEST_PRECEDENCE);

    assertThat(liveGate).isLessThan(killSwitch).isLessThan(reconciliation);
    assertThat(crossTenant).isLessThan(killSwitch).isLessThan(reconciliation);
  }
}
