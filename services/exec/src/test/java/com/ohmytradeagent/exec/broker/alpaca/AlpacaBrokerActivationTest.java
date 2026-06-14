package com.ohmytradeagent.exec.broker.alpaca;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import com.ohmytradeagent.exec.broker.stub.StubBroker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Wiring contract for the Alpaca adapter's broker.impl gating. Exercises every broker.impl
 * permutation (alpaca-live, alpaca-paper, stub, absent) plus the two fail-fast guards (missing
 * creds and live/paper endpoint coherence) in one ApplicationContextRunner sweep. Mirrors the house
 * bean-wiring style of {@code FillPollerWiringTest}.
 */
class AlpacaBrokerActivationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
          .withUserConfiguration(TestConfig.class);

  private static final String LIVE_HOST = "https://api.alpaca.markets";
  private static final String PAPER_HOST = "https://paper-api.alpaca.markets";

  @Configuration
  static class TestConfig {
    @Bean
    RestClient.Builder restClientBuilder() {
      return RestClient.builder();
    }

    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }

  @Test
  void aliveImplActivatesAlpacaAdapterAgainstLiveHost() {
    runner
        .withPropertyValues(
            "broker.impl=alpaca-live",
            "alpaca.api-key-id=dummy-key",
            "alpaca.api-secret-key=dummy-secret",
            "alpaca.base-url=" + LIVE_HOST)
        .withUserConfiguration(BrokerBeans.class)
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx).hasBean("alpacaRestClient");
              assertThat(ctx.getBeanNamesForType(OptionsBroker.class)).hasSize(1);
              assertThat(ctx.getBean(OptionsBroker.class)).isInstanceOf(AlpacaPaperBroker.class);
            });
  }

  @Test
  void paperImplStillActivatesAlpacaAdapterAgainstPaperHost() {
    runner
        .withPropertyValues(
            "broker.impl=alpaca-paper",
            "alpaca.api-key-id=dummy-key",
            "alpaca.api-secret-key=dummy-secret",
            "alpaca.base-url=" + PAPER_HOST)
        .withUserConfiguration(BrokerBeans.class)
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx).hasBean("alpacaRestClient");
              assertThat(ctx.getBeanNamesForType(OptionsBroker.class)).hasSize(1);
              assertThat(ctx.getBean(OptionsBroker.class)).isInstanceOf(AlpacaPaperBroker.class);
            });
  }

  @Test
  void stubImplSelectsStubBrokerAndNoAlpacaWiring() {
    runner
        .withPropertyValues("broker.impl=stub")
        .withUserConfiguration(BrokerBeans.class)
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx.getBeanNamesForType(OptionsBroker.class)).hasSize(1);
              assertThat(ctx.getBean(OptionsBroker.class)).isInstanceOf(StubBroker.class);
              assertThat(ctx).doesNotHaveBean("alpacaRestClient");
              assertThat(ctx).doesNotHaveBean(AlpacaConfig.class);
            });
  }

  @Test
  void absentImplDefaultsToStubBroker() {
    runner
        .withUserConfiguration(BrokerBeans.class)
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx.getBeanNamesForType(OptionsBroker.class)).hasSize(1);
              assertThat(ctx.getBean(OptionsBroker.class)).isInstanceOf(StubBroker.class);
              assertThat(ctx).doesNotHaveBean("alpacaRestClient");
              assertThat(ctx).doesNotHaveBean(AlpacaConfig.class);
            });
  }

  @Test
  void liveImplWithBlankApiKeyFailsFast() {
    runner
        .withPropertyValues(
            "broker.impl=alpaca-live",
            "alpaca.api-key-id=",
            "alpaca.api-secret-key=dummy-secret",
            "alpaca.base-url=" + LIVE_HOST)
        .withUserConfiguration(BrokerBeans.class)
        .run(
            ctx -> {
              assertThat(ctx).hasFailed();
              assertThat(ctx)
                  .getFailure()
                  .rootCause()
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("APCA_API_KEY_ID");
            });
  }

  @Test
  void liveImplPointedAtPaperHostFailsFast() {
    runner
        .withPropertyValues(
            "broker.impl=alpaca-live",
            "alpaca.api-key-id=dummy-key",
            "alpaca.api-secret-key=dummy-secret",
            "alpaca.base-url=" + PAPER_HOST)
        .withUserConfiguration(BrokerBeans.class)
        .run(
            ctx -> {
              assertThat(ctx).hasFailed();
              assertThat(ctx)
                  .getFailure()
                  .rootCause()
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("alpaca-live");
            });
  }

  /**
   * The conditionally-registered Alpaca beans + StubBroker. Kept separate from {@link TestConfig}
   * so the always-on collaborator beans (RestClient.Builder, MeterRegistry, ObjectMapper) are
   * present regardless of which broker.impl branch the @Conditional selects.
   */
  @Configuration
  @org.springframework.context.annotation.Import({
    AlpacaConfig.class,
    AlpacaPaperBroker.class,
    StubBroker.class
  })
  static class BrokerBeans {}
}
