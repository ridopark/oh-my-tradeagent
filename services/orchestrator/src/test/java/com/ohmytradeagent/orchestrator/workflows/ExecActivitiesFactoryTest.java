package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.temporal.failure.ApplicationFailure;
import org.junit.jupiter.api.Test;

/**
 * Phase 2c.2: validates the broker_target -> task-queue mapping and the whitelist that rejects
 * anything outside {@code paper|live|<provider>-(paper|live)}.
 *
 * <p>The full "dispatch lands on the right worker" assertion lives in {@link
 * CopytradeSignalWorkflowImplTest} (broker_target_routes_to_alpaca_paper_queue).
 */
class ExecActivitiesFactoryTest {

  @Test
  void taskQueueFor_alpacaPaper_returnsBrokerAlpacaPaper() {
    assertThat(ExecActivitiesFactory.taskQueueFor("alpaca-paper")).isEqualTo("broker-alpaca-paper");
  }

  @Test
  void taskQueueFor_legacyPaper_stillRoutes() {
    assertThat(ExecActivitiesFactory.taskQueueFor("paper")).isEqualTo("broker-paper");
  }

  @Test
  void taskQueueFor_tradierLive_returnsBrokerTradierLive() {
    assertThat(ExecActivitiesFactory.taskQueueFor("tradier-live")).isEqualTo("broker-tradier-live");
  }

  @Test
  void taskQueueFor_null_throwsNonRetryable() {
    assertThatThrownBy(() -> ExecActivitiesFactory.taskQueueFor(null))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            f -> {
              assertThat(f.getType()).isEqualTo("InvalidBrokerTargetError");
              assertThat(f.isNonRetryable()).isTrue();
            });
  }

  @Test
  void taskQueueFor_blank_throwsNonRetryable() {
    assertThatThrownBy(() -> ExecActivitiesFactory.taskQueueFor("  "))
        .isInstanceOf(ApplicationFailure.class)
        .hasMessageContaining("InvalidBrokerTargetError");
  }

  @Test
  void taskQueueFor_pathTraversal_throwsNonRetryable() {
    assertThatThrownBy(() -> ExecActivitiesFactory.taskQueueFor("../paper"))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            f -> {
              assertThat(f.getType()).isEqualTo("InvalidBrokerTargetError");
              assertThat(f.isNonRetryable()).isTrue();
            });
  }

  @Test
  void taskQueueFor_uppercaseRejected() {
    assertThatThrownBy(() -> ExecActivitiesFactory.taskQueueFor("Alpaca-Paper"))
        .isInstanceOf(ApplicationFailure.class)
        .hasMessageContaining("InvalidBrokerTargetError");
  }

  @Test
  void taskQueueFor_unknownEnvSuffixRejected() {
    assertThatThrownBy(() -> ExecActivitiesFactory.taskQueueFor("alpaca-prod"))
        .isInstanceOf(ApplicationFailure.class)
        .hasMessageContaining("InvalidBrokerTargetError");
  }
}
