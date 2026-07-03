package com.ohmytradeagent.exec.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.ohmytradeagent.exec.broker.alpaca.BrokerCredentialWriter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Dark proof for the teardown route. The {@code DELETE /internal/broker-credentials} controller
 * exists ONLY when {@code broker.creds.source=db} AND an {@code alpaca-*} impl is selected AND the
 * new {@code broker.credentials.delete.enabled} flag is true. The extra flag (default false) is
 * what keeps the delete route dark even on a DB-sourced alpaca pod that runs the write endpoint:
 * with the flag off the bean does not exist, so the route 404s for even an authenticated caller,
 * while the unaffected write controller stays up. Mirrors {@link
 * BrokerCredentialAccountDarkProofTest}.
 */
class BrokerCredentialDeleteDarkProofTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              BrokerCredentialDeleteAdminController.class, TestSupportConfig.class);

  @Test
  void flagOff_evenWithDbSourceAndAlpaca_noDeleteBean() {
    // The write path (source=db + alpaca) is up, but the delete flag is off (default) → the delete
    // controller is absent → the DELETE route 404s.
    runner
        .withPropertyValues("broker.impl=alpaca-paper", "broker.creds.source=db")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(BrokerCredentialDeleteAdminController.class));
  }

  @Test
  void flagOn_butSourceNotDb_noDeleteBean() {
    runner
        .withPropertyValues("broker.impl=alpaca-paper", "broker.credentials.delete.enabled=true")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(BrokerCredentialDeleteAdminController.class));
  }

  @Test
  void flagOn_butNonAlpacaImpl_noDeleteBean() {
    runner
        .withPropertyValues(
            "broker.impl=stub", "broker.creds.source=db", "broker.credentials.delete.enabled=true")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(BrokerCredentialDeleteAdminController.class));
  }

  @Test
  void flagOn_withDbSourceAndAlpaca_bringsUpDeleteBean() {
    runner
        .withPropertyValues(
            "broker.impl=alpaca-paper",
            "broker.creds.source=db",
            "broker.credentials.delete.enabled=true")
        .run(ctx -> assertThat(ctx).hasSingleBean(BrokerCredentialDeleteAdminController.class));
  }

  @Configuration
  static class TestSupportConfig {
    @Bean
    BrokerCredentialWriter brokerCredentialWriter() {
      return mock(BrokerCredentialWriter.class);
    }
  }
}
