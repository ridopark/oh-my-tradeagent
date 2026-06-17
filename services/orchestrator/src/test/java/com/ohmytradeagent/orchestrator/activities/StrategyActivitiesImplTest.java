package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.alert.WebhookClient;
import com.ohmytradeagent.orchestrator.alert.WebhookEmbed;
import com.ohmytradeagent.orchestrator.platform.CapitalAllocator;
import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import com.ohmytradeagent.orchestrator.platform.YamlStrategyRegistry.StrategyNotFoundException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * C1 (P0c-b2) observability for the live strategy-config read. {@link StrategyActivitiesImpl#get}
 * stays FAIL-CLOSED — every failure rethrows the original exception — while incrementing {@code
 * strategy_config_read_failures_total{tenant,strategy,reason}} and firing a deduplicated red
 * Discord alert (one per failure episode, one resolve on recovery). The metric/alert are
 * best-effort and must never alter the thrown exception.
 *
 * <p>Plain JUnit + Mockito + {@link SimpleMeterRegistry} — no DB, no Temporal.
 */
class StrategyActivitiesImplTest {

  private static final String COUNTER = "strategy_config_read_failures_total";
  private static final String TENANT = "acme";
  private static final String STRATEGY = "copytrade-v1";

  private StrategyRegistry registry;
  private CapitalAllocator capitalAllocator;
  private MeterRegistry meterRegistry;
  private WebhookClient webhookClient;
  private StrategyActivitiesImpl activities;

  @BeforeEach
  void setUp() {
    registry = mock(StrategyRegistry.class);
    capitalAllocator = mock(CapitalAllocator.class);
    meterRegistry = new SimpleMeterRegistry();
    webhookClient = mock(WebhookClient.class);
    activities =
        new StrategyActivitiesImpl(registry, capitalAllocator, meterRegistry, webhookClient);
  }

  private double counter(String reason) {
    var c =
        meterRegistry
            .find(COUNTER)
            .tag("tenant", TENANT)
            .tag("strategy", STRATEGY)
            .tag("reason", reason)
            .counter();
    return c == null ? 0.0 : c.count();
  }

  @Test
  void getReadFailureIncrementsCounterAndStillThrows() {
    StrategyNotFoundException boom = new StrategyNotFoundException("missing");
    when(registry.get(TENANT, STRATEGY)).thenThrow(boom);

    assertThatThrownBy(() -> activities.get(TENANT, STRATEGY)).isSameAs(boom);
    assertThat(counter("not_found")).isEqualTo(1.0);
  }

  @Test
  void getReadFailureFiresAlertOncePerCondition() {
    when(registry.get(TENANT, STRATEGY)).thenThrow(new StrategyNotFoundException("missing"));

    assertThatThrownBy(() -> activities.get(TENANT, STRATEGY))
        .isInstanceOf(StrategyNotFoundException.class);
    assertThatThrownBy(() -> activities.get(TENANT, STRATEGY))
        .isInstanceOf(StrategyNotFoundException.class);

    // Two failures → counter == 2, but the red alert fires exactly once (dedup, no per-retry spam).
    assertThat(counter("not_found")).isEqualTo(2.0);
    verify(webhookClient, times(1))
        .postEmbed(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(WebhookEmbed.class));
  }

  @Test
  void getSchemaVersionFailureTagged() {
    when(registry.get(TENANT, STRATEGY))
        .thenThrow(new IllegalStateException("strategy_config schema_version 2 exceeds build"));

    assertThatThrownBy(() -> activities.get(TENANT, STRATEGY))
        .isInstanceOf(IllegalStateException.class);
    assertThat(counter("schema_version")).isEqualTo(1.0);
  }

  @Test
  void getConfigParseFailureTagged() {
    // DbStrategyRegistry wraps a JsonProcessingException cause when the stored blob won't parse.
    when(registry.get(TENANT, STRATEGY))
        .thenThrow(
            new IllegalStateException(
                "Failed to deserialize strategy_config.config",
                new com.fasterxml.jackson.core.JsonParseException(null, "bad json")));

    assertThatThrownBy(() -> activities.get(TENANT, STRATEGY))
        .isInstanceOf(IllegalStateException.class);
    assertThat(counter("config_parse")).isEqualTo(1.0);
  }

  @Test
  void getDbErrorTagged() {
    // A genuine DB-I/O failure (no schema_version, no parse cause) buckets as db_error.
    when(registry.get(TENANT, STRATEGY)).thenThrow(new RuntimeException("connection refused"));

    assertThatThrownBy(() -> activities.get(TENANT, STRATEGY)).isInstanceOf(RuntimeException.class);
    assertThat(counter("db_error")).isEqualTo(1.0);
  }

  @Test
  void getSuccessAfterFailureFiresResolveAndRearms() {
    StrategyConfig cfg = new StrategyConfig();
    when(registry.get(TENANT, STRATEGY))
        .thenThrow(new StrategyNotFoundException("missing")) // 1st: fail
        .thenReturn(cfg) // 2nd: recover
        .thenThrow(new StrategyNotFoundException("missing again")); // 3rd: fail again

    // fail → one red alert
    assertThatThrownBy(() -> activities.get(TENANT, STRATEGY))
        .isInstanceOf(StrategyNotFoundException.class);
    // success → one resolve alert + key re-armed
    assertThat(activities.get(TENANT, STRATEGY)).isSameAs(cfg);
    // fail again → alerts again (key was re-armed by the success)
    assertThatThrownBy(() -> activities.get(TENANT, STRATEGY))
        .isInstanceOf(StrategyNotFoundException.class);

    // 2 failure alerts + 1 resolve alert = 3 embeds total.
    verify(webhookClient, times(3))
        .postEmbed(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(WebhookEmbed.class));
    assertThat(counter("not_found")).isEqualTo(2.0);
  }

  @Test
  void getSuccessDoesNotIncrementOrAlert() {
    StrategyConfig cfg = new StrategyConfig();
    when(registry.get(TENANT, STRATEGY)).thenReturn(cfg);

    assertThat(activities.get(TENANT, STRATEGY)).isSameAs(cfg);

    assertThat(counter("not_found")).isEqualTo(0.0);
    verify(webhookClient, never())
        .postEmbed(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(WebhookEmbed.class));
  }
}
