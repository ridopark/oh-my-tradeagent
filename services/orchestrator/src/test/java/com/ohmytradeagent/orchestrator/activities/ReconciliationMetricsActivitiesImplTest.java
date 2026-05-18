package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReconciliationMetricsActivitiesImplTest {

  private static final String TENANT = "dev";
  private static final String STRATEGY = "copytrade-v1";
  private static final String BROKER_TARGET = "alpaca-paper";

  private MeterRegistry registry;
  private ReconciliationMetricsActivitiesImpl activity;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    activity = new ReconciliationMetricsActivitiesImpl(registry);
  }

  @Test
  void recordCycle_incrementsIntentsCounterBySuppliedCount() {
    activity.recordCycle(TENANT, STRATEGY, BROKER_TARGET, 100L, 0L, 7L);

    Counter intents =
        registry.get(ReconciliationMetricsActivitiesImpl.INTENTS_COUNTER_NAME).counter();
    assertThat(intents.count()).isEqualTo(7.0);
  }

  @Test
  void recordCycle_singleOrphan_incrementsDiscrepanciesByExactlyOne() {
    // Acceptance criterion #2: synthetic single-orphan cycle increments discrepancies by exactly 1.
    activity.recordCycle(TENANT, STRATEGY, BROKER_TARGET, 100L, 1L, 3L);

    Counter disc =
        registry.get(ReconciliationMetricsActivitiesImpl.DISCREPANCIES_COUNTER_NAME).counter();
    assertThat(disc.count()).isEqualTo(1.0);
  }

  @Test
  void recordCycle_lagSampleLandsOnTimer() {
    activity.recordCycle(TENANT, STRATEGY, BROKER_TARGET, 1500L, 0L, 1L);

    Timer lag = registry.get(ReconciliationMetricsActivitiesImpl.LAG_TIMER_NAME).timer();
    assertThat(lag.count()).isEqualTo(1L);
    assertThat(lag.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(1500.0);
  }

  @Test
  void recordCycle_allMetersTaggedWithTenantStrategyBrokerTarget() {
    activity.recordCycle(TENANT, STRATEGY, BROKER_TARGET, 100L, 0L, 1L);

    assertTagsPresent(
        registry.get(ReconciliationMetricsActivitiesImpl.LAG_TIMER_NAME).timer().getId().getTags());
    assertTagsPresent(
        registry
            .get(ReconciliationMetricsActivitiesImpl.DISCREPANCIES_COUNTER_NAME)
            .counter()
            .getId()
            .getTags());
    assertTagsPresent(
        registry
            .get(ReconciliationMetricsActivitiesImpl.INTENTS_COUNTER_NAME)
            .counter()
            .getId()
            .getTags());
  }

  @Test
  void recordCycle_multipleCalls_accumulateOnSameMeters() {
    activity.recordCycle(TENANT, STRATEGY, BROKER_TARGET, 100L, 1L, 5L);
    activity.recordCycle(TENANT, STRATEGY, BROKER_TARGET, 200L, 2L, 10L);

    Counter disc =
        registry.get(ReconciliationMetricsActivitiesImpl.DISCREPANCIES_COUNTER_NAME).counter();
    Counter intents =
        registry.get(ReconciliationMetricsActivitiesImpl.INTENTS_COUNTER_NAME).counter();
    Timer lag = registry.get(ReconciliationMetricsActivitiesImpl.LAG_TIMER_NAME).timer();

    assertThat(disc.count()).isEqualTo(3.0);
    assertThat(intents.count()).isEqualTo(15.0);
    assertThat(lag.count()).isEqualTo(2L);
    assertThat(lag.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(300.0);
  }

  @Test
  void recordCycle_distinctBrokerTargets_produceDistinctMeters() {
    activity.recordCycle(TENANT, STRATEGY, "alpaca-paper", 100L, 1L, 5L);
    activity.recordCycle(TENANT, STRATEGY, "alpaca-live", 200L, 0L, 7L);

    Counter discPaper =
        registry
            .get(ReconciliationMetricsActivitiesImpl.DISCREPANCIES_COUNTER_NAME)
            .tags(Tags.of("broker_target", "alpaca-paper"))
            .counter();
    Counter discLive =
        registry
            .get(ReconciliationMetricsActivitiesImpl.DISCREPANCIES_COUNTER_NAME)
            .tags(Tags.of("broker_target", "alpaca-live"))
            .counter();

    assertThat(discPaper.count()).isEqualTo(1.0);
    assertThat(discLive.count()).isEqualTo(0.0);
  }

  private static void assertTagsPresent(Iterable<Tag> tags) {
    Set<String> keys =
        java.util.stream.StreamSupport.stream(tags.spliterator(), false)
            .map(Tag::getKey)
            .collect(Collectors.toSet());
    assertThat(keys).contains("tenant", "strategy", "broker_target");
  }
}
