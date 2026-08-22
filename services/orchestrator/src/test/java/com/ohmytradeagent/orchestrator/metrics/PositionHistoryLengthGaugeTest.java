package com.ohmytradeagent.orchestrator.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.ohmytradeagent.orchestrator.alert.WebhookClient;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.temporal.client.WorkflowClient;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Issue #752 Phase 1. Exercises everything below the Temporal fetch via the package-private {@code
 * update} seam: tag parsing from real-shape position workflow ids, gauge register/refresh,
 * deregistration on close (the leak case), the warn-level page firing exactly once per position,
 * and malformed ids being refused rather than guessed.
 */
class PositionHistoryLengthGaugeTest {

  private static final String DRAM_ID =
      "t-prod_real/s-copytrade-v1/pos/DRAM  270319C00100000/"
          + "chat-messages-769797179992571914-1538910809579851818:0";
  private static final String TSLA_ID =
      "t-prod-kipark/s-copytrade-v1/pos/TSLA  260918C00500000/sig-123:0";

  private SimpleMeterRegistry registry;
  private RecordingWebhook webhook;
  private PositionHistoryLengthGauge gauge;
  private long originalWarn;

  private static final class RecordingWebhook implements WebhookClient {
    final List<String> tenants = new ArrayList<>();
    final List<String> messages = new ArrayList<>();

    @Override
    public void post(String content) {
      tenants.add(null);
      messages.add(content);
    }

    @Override
    public void post(String tenantId, String content) {
      tenants.add(tenantId);
      messages.add(content);
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    registry = new SimpleMeterRegistry();
    webhook = new RecordingWebhook();
    gauge = new PositionHistoryLengthGauge(mock(WorkflowClient.class), registry, webhook);
    originalWarn = PositionHistoryLengthGauge.warnHistoryLength;
  }

  @AfterEach
  void tearDown() throws Exception {
    setWarn(originalWarn);
    registry.close();
  }

  private static void setWarn(long v) throws Exception {
    Field f = PositionHistoryLengthGauge.class.getDeclaredField("warnHistoryLength");
    f.setAccessible(true);
    f.set(null, v);
  }

  private Gauge find(String tenant) {
    return registry.find("temporal_workflow_history_length").tag("tenant_id", tenant).gauge();
  }

  @Test
  void registersGaugeWithParsedTags_andReportsLength() {
    gauge.update(Map.of(DRAM_ID, 42L));

    Gauge g = find("prod_real");
    assertThat(g).isNotNull();
    assertThat(g.value()).isEqualTo(42.0);
    assertThat(g.getId().getTag("workflow_type")).isEqualTo("PositionWorkflow");
    assertThat(g.getId().getTag("strategy_id")).isEqualTo("copytrade-v1");
    assertThat(g.getId().getTag("contract_symbol")).isEqualTo("DRAM  270319C00100000");
  }

  @Test
  void refreshesExistingGauge_withoutDuplicating() {
    gauge.update(Map.of(DRAM_ID, 42L));
    gauge.update(Map.of(DRAM_ID, 250L));

    assertThat(
            registry
                .find("temporal_workflow_history_length")
                .tag("tenant_id", "prod_real")
                .gauges())
        .hasSize(1);
    assertThat(find("prod_real").value()).isEqualTo(250.0);
  }

  @Test
  void deregistersClosedPositions() {
    Map<String, Long> both = new LinkedHashMap<>();
    both.put(DRAM_ID, 42L);
    both.put(TSLA_ID, 99L);
    gauge.update(both);
    assertThat(registry.find("temporal_workflow_history_length").gauges()).hasSize(2);

    gauge.update(Map.of(DRAM_ID, 43L));

    assertThat(registry.find("temporal_workflow_history_length").gauges()).hasSize(1);
    assertThat(find("prod-kipark")).isNull();
    assertThat(find("prod_real").value()).isEqualTo(43.0);
  }

  @Test
  void pagesOncePerPosition_whenWarnLevelCrossed() throws Exception {
    setWarn(100L);

    gauge.update(Map.of(DRAM_ID, 99L));
    assertThat(webhook.messages).isEmpty();

    gauge.update(Map.of(DRAM_ID, 100L));
    assertThat(webhook.messages).hasSize(1);
    assertThat(webhook.tenants.get(0)).isEqualTo("prod_real");
    assertThat(webhook.messages.get(0))
        .contains("100")
        .contains("DRAM  270319C00100000")
        .contains("#752");

    // Still above warn on the next poll — must NOT page again.
    gauge.update(Map.of(DRAM_ID, 150L));
    assertThat(webhook.messages).hasSize(1);
  }

  @Test
  void pagesAgain_ifPositionClosesAndANewOneOpensPastWarn() throws Exception {
    setWarn(100L);
    gauge.update(Map.of(DRAM_ID, 150L));
    gauge.update(Map.of(TSLA_ID, 150L)); // DRAM closed, TSLA opened already past warn

    assertThat(webhook.messages).hasSize(2);
    assertThat(webhook.tenants).containsExactly("prod_real", "prod-kipark");
  }

  @Test
  void malformedId_isRefusedNotGuessed() {
    gauge.update(Map.of("not-a-position-id", 42L, DRAM_ID, 7L));

    assertThat(registry.find("temporal_workflow_history_length").gauges()).hasSize(1);
    assertThat(find("prod_real").value()).isEqualTo(7.0);
  }

  @Test
  void webhookFailure_doesNotBreakTheScrape() throws Exception {
    setWarn(10L);
    PositionHistoryLengthGauge throwing =
        new PositionHistoryLengthGauge(
            mock(WorkflowClient.class),
            registry,
            new WebhookClient() {
              @Override
              public void post(String content) {
                throw new IllegalStateException("discord down");
              }
            });

    throwing.update(Map.of(DRAM_ID, 50L)); // must not throw
    assertThat(find("prod_real").value()).isEqualTo(50.0);
  }
}
