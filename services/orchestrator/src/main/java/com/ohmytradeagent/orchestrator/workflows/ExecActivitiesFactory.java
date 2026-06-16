package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.orchestrator.activities.ExecActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.Map;
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

  /**
   * Plan-2A R-AA-6: explicit backoff so a transient broker/journal hiccup retries with spacing
   * instead of Temporal's default tight schedule. Spacing out the (up to {@link #MAX_ATTEMPTS})
   * attempts *reduces* the dup-422 trigger on the order path: a 422-on-retry from a duplicate
   * client_order_id is far less likely when the first attempt has had a real moment to settle at
   * the broker before the next fires (Plan-1 B1 already fixes the crash; this only narrows the
   * window).
   *
   * <p>startToClose stays at {@link #DEFAULT_START_TO_CLOSE} (15s) for ALL THREE exec activities —
   * this stub is shared by placeOrder + cancelOrder + reads, and the over-sell gate relies on the
   * cancel/read paths staying snappy near expiry. We add backoff only (no startToClose change), so
   * no version gate is required (plain options).
   */
  private static final Duration RETRY_INITIAL_INTERVAL = Duration.ofMillis(500);

  private static final double RETRY_BACKOFF_COEFFICIENT = 2.0;

  private static final Duration RETRY_MAXIMUM_INTERVAL = Duration.ofSeconds(5);

  /**
   * Temporal activity-type name for {@link ExecActivities#placeOrder} — the default is the
   * capitalized method name (no {@code @ActivityMethod(name=...)} override on the interface).
   * Pinned as a constant so the per-method options map below is not a stringly-typed footgun: a
   * typo here would silently fall back to {@link #DEFAULT_START_TO_CLOSE}/{@link #MAX_ATTEMPTS} and
   * re-open the exact 5xx gap this widening closes. Asserted in {@code ExecActivitiesFactoryTest}.
   */
  static final String PLACE_ORDER_ACTIVITY_NAME = "PlaceOrder";

  /**
   * Order-submission retry budget (widened beyond the shared default for {@code PlaceOrder} ONLY).
   *
   * <p>WHY: a transient broker 5xx (Alpaca {@code code 50010000}) on an entry/exit submission must
   * survive a ~1–2 min blip rather than dropping a live order. The shared default ({@link
   * #MAX_ATTEMPTS}=5 attempts with the 500ms→×2→5s backoff above) exhausts in well under a minute,
   * so a 500 lasting ~1 min failed the workflow — the real incident was a {@code SPY
   * 260622C00755000} BTO that took an HTTP 500 and died after the bounded retries. 6 attempts under
   * a 180s schedule-to-close ceiling rides through a 1–2 min blip.
   *
   * <p>SAFE TO RETRY: every order this workflow places is a LIMIT order (the intent carries a
   * {@code limit_price}), so retrying through a blip can never overpay — the worst case is no fill,
   * never a worse fill. Non-retryable 4xx (401/403 auth, insufficient-funds, invalid-contract,
   * non-duplicate 422) are still classified {@code nonRetryable} in {@code
   * AlpacaPaperBroker.mapError} and fail fast — this widening only gives the genuinely-transient
   * 5xx path more headroom.
   *
   * <p>BOUNDED: 6 attempts / 180s is still a hard ceiling, so a genuine broker outage fails-closed
   * (and alerts) rather than retrying forever.
   *
   * <p>SCOPE: applied via a per-activity options override so cancelOrder + getOrderStatus keep the
   * snappy shared default the over-sell gate depends on near expiry — only the order-submission
   * path widens. startToClose is unchanged (15s).
   */
  private static final int PLACE_ORDER_MAX_ATTEMPTS = 6;

  private static final Duration PLACE_ORDER_SCHEDULE_TO_CLOSE = Duration.ofSeconds(180);

  private ExecActivitiesFactory() {}

  /**
   * Returns an {@link ExecActivities} stub pinned to the {@code broker-<brokerTarget>} task queue.
   *
   * @throws io.temporal.failure.ApplicationFailure non-retryable {@code InvalidBrokerTargetError}
   *     when {@code brokerTarget} is null/blank or fails the whitelist.
   */
  static ExecActivities forTarget(String brokerTarget) {
    String taskQueue = taskQueueFor(brokerTarget);
    // Shared default: cancelOrder + getOrderStatus (snappy; over-sell gate depends on these near
    // expiry). placeOrder overrides with the widened 5xx-survival budget below.
    ActivityOptions defaults =
        ActivityOptions.newBuilder()
            .setTaskQueue(taskQueue)
            .setStartToCloseTimeout(DEFAULT_START_TO_CLOSE)
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setMaximumAttempts(MAX_ATTEMPTS)
                    .setInitialInterval(RETRY_INITIAL_INTERVAL)
                    .setBackoffCoefficient(RETRY_BACKOFF_COEFFICIENT)
                    .setMaximumInterval(RETRY_MAXIMUM_INTERVAL)
                    .build())
            .build();
    return Workflow.newActivityStub(
        ExecActivities.class,
        defaults,
        Map.of(PLACE_ORDER_ACTIVITY_NAME, placeOrderOptions(taskQueue)));
  }

  /**
   * Order-submission ({@code PlaceOrder}) activity options: the widened 5xx-survival budget ({@link
   * #PLACE_ORDER_MAX_ATTEMPTS} attempts under a {@link #PLACE_ORDER_SCHEDULE_TO_CLOSE} ceiling),
   * same 15s start-to-close and same backoff as the shared default. Package-visible so the
   * regression guard in {@code ExecActivitiesFactoryTest} can assert the budget never silently
   * shrinks back below the 6-attempt / 180s floor.
   */
  static ActivityOptions placeOrderOptions(String taskQueue) {
    return ActivityOptions.newBuilder()
        .setTaskQueue(taskQueue)
        .setStartToCloseTimeout(DEFAULT_START_TO_CLOSE)
        .setScheduleToCloseTimeout(PLACE_ORDER_SCHEDULE_TO_CLOSE)
        .setRetryOptions(
            RetryOptions.newBuilder()
                .setMaximumAttempts(PLACE_ORDER_MAX_ATTEMPTS)
                .setInitialInterval(RETRY_INITIAL_INTERVAL)
                .setBackoffCoefficient(RETRY_BACKOFF_COEFFICIENT)
                .setMaximumInterval(RETRY_MAXIMUM_INTERVAL)
                .build())
        .build();
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
