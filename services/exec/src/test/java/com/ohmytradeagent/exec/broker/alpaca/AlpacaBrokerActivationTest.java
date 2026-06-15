package com.ohmytradeagent.exec.broker.alpaca;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.exec.broker.BrokerClientRegistry;
import com.ohmytradeagent.exec.broker.BrokerCredentialSource;
import com.ohmytradeagent.exec.broker.stub.StubBroker;
import com.ohmytradeagent.exec.broker.stub.StubBrokerClientRegistry;
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
 * Wiring contract for the P4-a broker registry's broker.impl gating. Exercises every broker.impl
 * permutation (alpaca-live, alpaca-paper, stub, absent) and asserts that EXACTLY ONE {@link
 * BrokerClientRegistry} bean is selected per profile: the {@link AlpacaBrokerClientRegistry} +
 * {@link EnvFallbackBrokerCredentialSource} for {@code alpaca-*}, the {@link
 * StubBrokerClientRegistry} for {@code stub}/absent (no alpaca credential source, no RestClient
 * built).
 *
 * <p>The cred-presence + live/paper coherence FAIL-FAST guards moved into the registry build
 * ({@link AlpacaModeCoherence}); they are unit-pinned in {@link AlpacaModeCoherenceTest}. The boot
 * account probe (now a registry warm-up) is pinned in {@link AlpacaAccountIdentityProbeTest}.
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
  void liveImplSelectsAlpacaRegistryAndEnvFallbackSource() {
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
              assertThat(ctx.getBeanNamesForType(BrokerClientRegistry.class)).hasSize(1);
              assertThat(ctx.getBean(BrokerClientRegistry.class))
                  .isInstanceOf(AlpacaBrokerClientRegistry.class);
              assertThat(ctx.getBeanNamesForType(BrokerCredentialSource.class)).hasSize(1);
              assertThat(ctx.getBean(BrokerCredentialSource.class))
                  .isInstanceOf(EnvFallbackBrokerCredentialSource.class);
              assertThat(ctx).doesNotHaveBean(StubBrokerClientRegistry.class);
            });
  }

  @Test
  void paperImplSelectsAlpacaRegistry() {
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
              assertThat(ctx.getBeanNamesForType(BrokerClientRegistry.class)).hasSize(1);
              assertThat(ctx.getBean(BrokerClientRegistry.class))
                  .isInstanceOf(AlpacaBrokerClientRegistry.class);
            });
  }

  @Test
  void fileCredsSourceSelectsFileMountedSource() {
    // broker.creds.source=file → exactly one BrokerCredentialSource, the per-tenant file source.
    runner
        .withPropertyValues(
            "broker.impl=alpaca-paper",
            "broker.creds.source=file",
            "alpaca.api-key-id=dummy-key",
            "alpaca.api-secret-key=dummy-secret",
            "alpaca.base-url=" + PAPER_HOST)
        .withUserConfiguration(BrokerBeans.class)
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx.getBeanNamesForType(BrokerCredentialSource.class)).hasSize(1);
              assertThat(ctx.getBean(BrokerCredentialSource.class))
                  .isInstanceOf(FileMountedBrokerCredentialSource.class);
              assertThat(ctx).doesNotHaveBean(EnvFallbackBrokerCredentialSource.class);
            });
  }

  @Test
  void explicitEnvCredsSourceSelectsEnvFallbackSource() {
    runner
        .withPropertyValues(
            "broker.impl=alpaca-paper",
            "broker.creds.source=env",
            "alpaca.api-key-id=dummy-key",
            "alpaca.api-secret-key=dummy-secret",
            "alpaca.base-url=" + PAPER_HOST)
        .withUserConfiguration(BrokerBeans.class)
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx.getBeanNamesForType(BrokerCredentialSource.class)).hasSize(1);
              assertThat(ctx.getBean(BrokerCredentialSource.class))
                  .isInstanceOf(EnvFallbackBrokerCredentialSource.class);
              assertThat(ctx).doesNotHaveBean(FileMountedBrokerCredentialSource.class);
            });
  }

  @Test
  void unrecognizedCredsSourceYieldsNoSourceAndFailsContext() {
    // MUST-FIX-2: a typo'd selector must fail-closed-LOUD (zero sources → the registry's required
    // BrokerCredentialSource ctor arg is unsatisfied → context fails), never silently default env.
    runner
        .withPropertyValues(
            "broker.impl=alpaca-paper",
            "broker.creds.source=garbage",
            "alpaca.api-key-id=dummy-key",
            "alpaca.api-secret-key=dummy-secret",
            "alpaca.base-url=" + PAPER_HOST)
        .withUserConfiguration(BrokerBeans.class)
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  @Test
  void stubImplSelectsStubRegistryAndNoAlpacaWiring() {
    runner
        .withPropertyValues("broker.impl=stub")
        .withUserConfiguration(BrokerBeans.class)
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx.getBeanNamesForType(BrokerClientRegistry.class)).hasSize(1);
              assertThat(ctx.getBean(BrokerClientRegistry.class))
                  .isInstanceOf(StubBrokerClientRegistry.class);
              assertThat(ctx).doesNotHaveBean(AlpacaBrokerClientRegistry.class);
              assertThat(ctx).doesNotHaveBean(BrokerCredentialSource.class);
              assertThat(ctx).doesNotHaveBean(AlpacaConfig.class);
            });
  }

  @Test
  void absentImplDefaultsToStubRegistry() {
    runner
        .withUserConfiguration(BrokerBeans.class)
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx.getBeanNamesForType(BrokerClientRegistry.class)).hasSize(1);
              assertThat(ctx.getBean(BrokerClientRegistry.class))
                  .isInstanceOf(StubBrokerClientRegistry.class);
              assertThat(ctx).doesNotHaveBean(AlpacaBrokerClientRegistry.class);
              assertThat(ctx).doesNotHaveBean(BrokerCredentialSource.class);
              assertThat(ctx).doesNotHaveBean(AlpacaConfig.class);
            });
  }

  /**
   * The conditionally-registered Alpaca + stub beans. Kept separate from {@link TestConfig} so the
   * always-on collaborator beans (RestClient.Builder, MeterRegistry, ObjectMapper) are present
   * regardless of which broker.impl branch the @Conditional selects.
   */
  @Configuration
  @org.springframework.context.annotation.Import({
    AlpacaConfig.class,
    EnvFallbackBrokerCredentialSource.class,
    FileMountedBrokerCredentialSource.class,
    AlpacaBrokerClientRegistry.class,
    StubBroker.class,
    StubBrokerClientRegistry.class
  })
  static class BrokerBeans {}
}
