package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import com.ohmytradeagent.orchestrator.platform.StrategyConfigWriter;
import com.ohmytradeagent.orchestrator.platform.TenantConfigWriter;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the boot-time activity-type collision that crash-looped the orchestrator.
 *
 * <p>Both {@link StrategyConfigUpdateActivities#update} and {@link TenantConfigUpdateActivities}'s
 * {@code update} defaulted to the Temporal activity type {@code "Update"} (the capitalized method
 * name). Registering both on the shared {@code orchestrator-core} worker threw {@code
 * TypeAlreadyRegisteredException} at boot (see {@code TemporalWorkerConfig.worker}), so the WHOLE
 * service failed to start. This asserts both impls register on one worker without a type collision
 * — i.e. their activity type names stay distinct.
 */
class UpdateActivityTypeRegistrationTest {

  private final TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();

  @AfterEach
  void tearDown() {
    env.close();
  }

  @Test
  void bothUpdateActivitiesRegisterOnOneWorker_noTypeCollision() {
    Worker worker = env.newWorker("orchestrator-core");
    assertThatCode(
            () ->
                worker.registerActivitiesImplementations(
                    new StrategyConfigUpdateActivitiesImpl(mock(StrategyConfigWriter.class)),
                    new TenantConfigUpdateActivitiesImpl(mock(TenantConfigWriter.class))))
        .doesNotThrowAnyException();
  }
}
