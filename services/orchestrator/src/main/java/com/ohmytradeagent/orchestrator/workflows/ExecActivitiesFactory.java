package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.orchestrator.activities.ExecActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 2c.2 helper: builds an {@link ExecActivities} stub whose Activity calls route to the task
 * queue derived from {@code StrategyConfig.broker_target} ({@code broker-<value>}).
 *
 * <p>Determinism: called inside the workflow body. The input is the broker_target string fetched
 * from {@link com.ohmytradeagent.contract.StrategyConfig#getBrokerTarget()} (a deterministic
 * StrategyActivities lookup against tenants config), so replays reconstruct the same task queue.
 *
 * <p>Validation: a small whitelist regex admits the legacy {@code paper} / {@code live} values (so
 * audit-record deserialization still works) plus the Phase 2c.2 {@code <provider>-<env>} shape
 * (e.g. {@code alpaca-paper}). For ROUTING, however, the legacy bare values are rejected: no worker
 * polls {@code broker-paper} / {@code broker-live}, so accepting them at this layer would hang the
 * workflow at the Activity {@code StartToCloseTimeout}. Bare values produce a non-retryable {@code
 * InvalidBrokerTargetError} that fails the workflow fast and points at the misconfigured tenant.
 */
final class ExecActivitiesFactory {

  private static final Logger log = LoggerFactory.getLogger(ExecActivitiesFactory.class);

  static final String TASK_QUEUE_PREFIX = "broker-";

  /**
   * Values that the schema admits for back-compat with pre-2c.2 audit records but that no worker
   * polls. {@link #taskQueueFor} rejects these with {@code InvalidBrokerTargetError} so the
   * workflow fails fast instead of hanging on a StartToCloseTimeout.
   */
  static final Set<String> LEGACY_BARE_TARGETS = Set.of("paper", "live");

  private static final Duration DEFAULT_START_TO_CLOSE = Duration.ofSeconds(15);

  /**
   * Issue #264: bound the exec-activity retry policy. The stub previously set only task-queue +
   * start-to-close, leaving Temporal's default activity policy (unbounded retries with NPEs treated
   * as retryable) — so a programming error such as a null {@code OrderIntent.brokerTarget} NPE
   * looped to 1637+ attempts. The {@code InvalidOrderIntentError} non-retryable failure added in
   * {@code ExecActivitiesImpl.placeOrder} already terminates that specific path; this max-attempts
   * cap is the defense-in-depth backstop so <em>any</em> future programming error in the activity
   * fails the workflow fast instead of spinning forever. 5 attempts mirrors the bounded-retry
   * precedent at {@code CopytradeSignalWorkflowImpl} (pre-trade check, {@code
   * setMaximumAttempts(3)}) with a little extra headroom for genuinely-transient broker/journal
   * hiccups on the order path.
   */
  private static final int MAX_ATTEMPTS = 5;

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
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(MAX_ATTEMPTS).build())
            .build());
  }

  /** Visible for tests + ReconciliationWorkflow re-use. */
  static String taskQueueFor(String brokerTarget) {
    if (brokerTarget == null || brokerTarget.isBlank()) {
      throw ApplicationFailure.newNonRetryableFailure(
          "broker_target is required but was null/blank", "InvalidBrokerTargetError");
    }
    if (!BrokerTargetValidator.isValid(brokerTarget)) {
      throw ApplicationFailure.newNonRetryableFailure(
          "broker_target rejected by whitelist: " + brokerTarget, "InvalidBrokerTargetError");
    }
    if (LEGACY_BARE_TARGETS.contains(brokerTarget)) {
      // Outside Workflow.* this is a plain SLF4J log; the call site is the workflow body, but
      // logging is allowed inside workflows as long as it's deterministic (no time, no random).
      log.warn(
          "broker_target='{}' is deserialization-only (no worker polls broker-{}); failing fast"
              + " — update tenant config to '<provider>-paper' / '<provider>-live'.",
          brokerTarget,
          brokerTarget);
      throw ApplicationFailure.newNonRetryableFailure(
          "Legacy broker_target '"
              + brokerTarget
              + "' has no worker queue. Update tenant config to '<provider>-paper' /"
              + " '<provider>-live'.",
          "InvalidBrokerTargetError");
    }
    return TASK_QUEUE_PREFIX + brokerTarget;
  }
}
