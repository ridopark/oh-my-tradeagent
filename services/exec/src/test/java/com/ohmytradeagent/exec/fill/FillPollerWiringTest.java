package com.ohmytradeagent.exec.fill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.ohmytradeagent.exec.broker.BrokerClientRegistry;
import com.ohmytradeagent.exec.journal.OrderIntentJournal;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.test.context.TestPropertySource;

/**
 * Regression: a previous /simplify pass changed {@link FillPoller}'s {@code @Scheduled} cadence to
 * a SpEL expression {@code #{@fillPollerProperties.intervalMs}}, but Spring registers
 * {@code @ConfigurationProperties} records under {@code <prefix>-<fqcn>} bean names, not the
 * camelCased simple-class name. The SpEL failed at context refresh, the pod crash-looped in
 * production. This test boots a slice with {@code poll.enabled=true} so {@code @EnableScheduling}
 * fires {@code @Scheduled} SpEL resolution as part of context refresh — exactly the path that
 * tripped in production.
 */
@SpringBootTest(classes = FillPollerWiringTest.Config.class)
@TestPropertySource(
    properties = {
      "exec.fill-listener.poll.enabled=true",
      "exec.fill-listener.poll.interval-ms=30000",
      "exec.fill-listener.poll.grace-ms=60000",
      "exec.fill-listener.poll.batch-size=50"
    })
class FillPollerWiringTest {

  @Configuration
  @EnableScheduling
  @ComponentScan(
      basePackageClasses = FillPoller.class,
      // Exclude sibling test-only Config inner classes so two wiring tests in this package don't
      // crash each other by registering the same dependency beans under different names.
      excludeFilters =
          @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*WiringTest\\$Config"))
  static class Config {
    // Distinct bean names so this test's Config doesn't collide with
    // FillDispatcherWiringTest's Config when both end up on the test scan path.
    @Bean
    WorkflowClient pollerWiringWorkflowClient() {
      return mock(WorkflowClient.class);
    }

    @Bean
    OrderIntentJournal pollerWiringJournal() {
      return mock(OrderIntentJournal.class);
    }

    @Bean
    BrokerClientRegistry pollerWiringBrokerRegistry() {
      return mock(BrokerClientRegistry.class);
    }

    @Bean
    MeterRegistry pollerWiringMeterRegistry() {
      return new SimpleMeterRegistry();
    }
  }

  @Autowired FillPoller poller;

  @Test
  void fillPollerContextStartsCleanly() {
    assertThat(poller).isNotNull();
  }
}
