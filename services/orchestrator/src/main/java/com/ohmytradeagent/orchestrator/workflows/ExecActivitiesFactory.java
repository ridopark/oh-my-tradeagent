package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.orchestrator.activities.ExecActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Phase 2c.2 helper: builds an {@link ExecActivities} stub whose Activity calls route to the task
 * queue derived from {@code StrategyConfig.broker_target} ({@code broker-<value>}).
 *
 * <p>Determinism: called inside the workflow body. The input is the broker_target string fetched
 * from {@link com.ohmytradeagent.contract.StrategyConfig#getBrokerTarget()} (a deterministic
 * StrategyActivities lookup against tenants config), so replays reconstruct the same task queue.
 *
 * <p>Validation: a small whitelist regex rejects path traversal and similar shapes. The legacy
 * {@code paper} / {@code live} values are admitted for back-compat with pre-2c.2 audit fixtures;
 * Phase 2c.2 tenants use the {@code <provider>-<env>} shape (e.g. {@code alpaca-paper}).
 */
final class ExecActivitiesFactory {

  static final String TASK_QUEUE_PREFIX = "broker-";

  /**
   * One of: {@code paper}, {@code live}, or {@code <provider>-<env>} where provider is lowercase
   * letters and env is {@code paper} or {@code live}. This matches the contract schema's
   * broker_target enum exactly.
   */
  static final Pattern VALID_TARGET = Pattern.compile("^(paper|live|[a-z]+-(paper|live))$");

  private static final Duration DEFAULT_START_TO_CLOSE = Duration.ofSeconds(15);

  private ExecActivitiesFactory() {}

  /**
   * Returns an {@link ExecActivities} stub pinned to the {@code broker-<brokerTarget>} task queue.
   *
   * @throws io.temporal.failure.ApplicationFailure non-retryable {@code InvalidBrokerTargetError}
   *     when {@code brokerTarget} is null/blank or fails the whitelist.
   */
  static ExecActivities forTarget(String brokerTarget) {
    return Workflow.newActivityStub(
        ExecActivities.class,
        ActivityOptions.newBuilder()
            .setTaskQueue(taskQueueFor(brokerTarget))
            .setStartToCloseTimeout(DEFAULT_START_TO_CLOSE)
            .build());
  }

  /** Visible for tests + ReconciliationWorkflow re-use. */
  static String taskQueueFor(String brokerTarget) {
    if (brokerTarget == null || brokerTarget.isBlank()) {
      throw ApplicationFailure.newNonRetryableFailure(
          "broker_target is required but was null/blank", "InvalidBrokerTargetError");
    }
    if (!VALID_TARGET.matcher(brokerTarget).matches()) {
      throw ApplicationFailure.newNonRetryableFailure(
          "broker_target rejected by whitelist: " + brokerTarget, "InvalidBrokerTargetError");
    }
    return TASK_QUEUE_PREFIX + brokerTarget;
  }
}
