package com.ohmytradeagent.exec.fill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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

/**
 * Pins the bean-wiring claim that {@link FillDispatcherImpl} wins over {@link NoopFillDispatcher}
 * via {@code @ConditionalOnMissingBean}. Spring's documentation calls out that conditional
 * evaluation on component-scanned beans depends on resolution order, so this contract is worth a
 * Spring-context test rather than inspection alone.
 */
@SpringBootTest(classes = FillDispatcherWiringTest.Config.class)
class FillDispatcherWiringTest {

  @Configuration
  @ComponentScan(
      basePackageClasses = FillDispatcherImpl.class,
      // Exclude sibling test-only Config inner classes so two wiring tests in this package don't
      // crash each other by registering the same dependency beans under different names.
      excludeFilters =
          @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*WiringTest\\$Config"))
  static class Config {
    @Bean
    WorkflowClient workflowClient() {
      return mock(WorkflowClient.class);
    }

    @Bean
    OrderIntentJournal orderIntentJournal() {
      return mock(OrderIntentJournal.class);
    }

    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }

  @Autowired FillDispatcher dispatcher;

  @Test
  void fillDispatcherImpl_winsOver_noopFillDispatcher() {
    assertThat(dispatcher)
        .as("@ConditionalOnMissingBean must let FillDispatcherImpl claim the FillDispatcher slot")
        .isInstanceOf(FillDispatcherImpl.class);
  }
}
