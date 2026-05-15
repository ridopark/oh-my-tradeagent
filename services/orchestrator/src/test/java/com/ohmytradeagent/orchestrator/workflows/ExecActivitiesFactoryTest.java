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

  /**
   * Phase 2c.2 review feedback (Major 2): the bare legacy {@code paper} / {@code live} values pass
   * the schema enum and the routing whitelist, but no worker polls {@code broker-paper} / {@code
   * broker-live}. Accepting them at the routing layer would hang the workflow at the Activity
   * {@code StartToCloseTimeout}. {@code taskQueueFor} must therefore fail fast with a non-retryable
   * {@code InvalidBrokerTargetError}.
   */
  @Test
  void legacy_paper_value_throws_InvalidBrokerTargetError() {
    assertThatThrownBy(() -> ExecActivitiesFactory.taskQueueFor("paper"))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            f -> {
              assertThat(f.getType()).isEqualTo("InvalidBrokerTargetError");
              assertThat(f.isNonRetryable()).isTrue();
              assertThat(f.getOriginalMessage()).contains("Legacy broker_target 'paper'");
            });
  }

  @Test
  void legacy_live_value_throws_InvalidBrokerTargetError() {
    assertThatThrownBy(() -> ExecActivitiesFactory.taskQueueFor("live"))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            f -> {
              assertThat(f.getType()).isEqualTo("InvalidBrokerTargetError");
              assertThat(f.isNonRetryable()).isTrue();
              assertThat(f.getOriginalMessage()).contains("Legacy broker_target 'live'");
            });
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
