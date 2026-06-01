package com.ohmytradeagent.orchestrator.activities;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Issue #323 account-snapshot dispatch-failure metrics impl. Holds the injected {@link
 * MeterRegistry} and increments {@link #DISPATCH_FAILURES_COUNTER_NAME} once per failed dispatch.
 * Symmetric to the #329 {@code openpositions_value_failures_total} counter ({@link
 * VisibilityPortfolioSnapshot#VALUE_FAILURES_COUNTER_NAME}) — same {@code Counter.builder(...)
 * .register(meterRegistry)} registration idiom, same {@code _total}-suffixed name, same per-tag
 * caching.
 *
 * <p>Counters are cached per {@code broker_target} tag (Micrometer dedupes by name+tags anyway;
 * caching avoids the lookup-and-register cost). Tag cardinality is bounded: {@code broker_target}
 * is the {@code <provider>-<env>} value already used by the workflow's routing — no per-correlation
 * labels.
 */
@Component
public class AccountSnapshotMetricsActivitiesImpl implements AccountSnapshotMetricsActivities {

  static final String DISPATCH_FAILURES_COUNTER_NAME = "accountsnapshot_dispatch_failures_total";

  private static final Logger log =
      LoggerFactory.getLogger(AccountSnapshotMetricsActivitiesImpl.class);

  private final MeterRegistry meterRegistry;
  private final ConcurrentMap<String, Counter> dispatchFailureCounters = new ConcurrentHashMap<>();

  public AccountSnapshotMetricsActivitiesImpl(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  @Override
  public void recordDispatchFailure(String brokerTarget) {
    Counter counter =
        dispatchFailureCounters.computeIfAbsent(
            brokerTarget,
            bt ->
                Counter.builder(DISPATCH_FAILURES_COUNTER_NAME)
                    .description(
                        "AccountSnapshotActivity dispatch failures that fail the notional-cap gate closed (#323 observability; does not affect the gate decision).")
                    .tag("broker_target", brokerTarget)
                    .register(meterRegistry));
    counter.increment();
    log.warn("accountSnapshot dispatch-failure recorded broker_target={}", brokerTarget);
  }
}
