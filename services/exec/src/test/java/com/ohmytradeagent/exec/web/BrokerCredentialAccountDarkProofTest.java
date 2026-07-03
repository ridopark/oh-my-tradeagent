package com.ohmytradeagent.exec.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A1 dark proof. The verified-account READ controller + reader exist ONLY when {@code
 * broker.creds.source=db} AND an {@code alpaca-*} impl is selected — exactly like the write-side
 * {@link BrokerCredentialAdminController}. On a homelab pod (selector at {@code env}) neither bean
 * exists, so the read endpoint 404s and adds zero attack surface.
 */
class BrokerCredentialAccountDarkProofTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              BrokerCredentialAccountController.class,
              BrokerCredentialAccountReader.class,
              TestSupportConfig.class);

  @Test
  void sourceNotDb_noReadBeans() {
    runner
        .withPropertyValues("broker.impl=alpaca-paper") // creds source left at env-default
        .run(
            ctx -> {
              assertThat(ctx).doesNotHaveBean(BrokerCredentialAccountController.class);
              assertThat(ctx).doesNotHaveBean(BrokerCredentialAccountReader.class);
            });
  }

  @Test
  void nonAlpacaImpl_noReadBeans_evenWithDbSource() {
    runner
        .withPropertyValues("broker.impl=stub", "broker.creds.source=db")
        .run(
            ctx -> {
              assertThat(ctx).doesNotHaveBean(BrokerCredentialAccountController.class);
              assertThat(ctx).doesNotHaveBean(BrokerCredentialAccountReader.class);
            });
  }

  @Test
  void dbSourceAndAlpacaImpl_bringsUpReadBeans() {
    runner
        .withPropertyValues("broker.impl=alpaca-paper", "broker.creds.source=db")
        .run(
            ctx -> {
              assertThat(ctx).hasSingleBean(BrokerCredentialAccountController.class);
              assertThat(ctx).hasSingleBean(BrokerCredentialAccountReader.class);
            });
  }

  @Configuration
  static class TestSupportConfig {
    @Bean
    DSLContext dslContext() {
      return mock(DSLContext.class);
    }
  }
}
