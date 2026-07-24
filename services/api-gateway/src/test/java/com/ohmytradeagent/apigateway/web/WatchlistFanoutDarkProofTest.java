package com.ohmytradeagent.apigateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.apigateway.security.ServiceTokenFilter;
import io.temporal.client.WorkflowClient;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 1 (Fork A) watchlist fan-out dark proof — the sibling of {@link
 * CopytradeFanoutDarkProofTest}. With {@code watchlist.fanout.enabled} UNSET (the repo default),
 * neither {@link WatchlistFanoutController} nor {@link ServiceTokenFilter} exists — the {@code GET
 * /internal/watchlist-fanout-targets} route is not mapped (404s).
 *
 * <p>The load-bearing assertion is {@link #fanoutFlagOn_bringsUpControllerAndAuthFilter()}:
 * enabling ONLY the watchlist fan-out flag (no broker.credentials.write / no operator.* admin flag)
 * must bring up BOTH the controller AND the bearer-gate filter. The filter's
 * {@code @ConditionalOnExpression} has to list this flag, or the internal registry route would be
 * reachable unauthenticated — any pod on the cluster network could enumerate the enabled watchlist
 * tenant set. Fails closed if the controller flag is added but the filter wiring is forgotten.
 */
class WatchlistFanoutDarkProofTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              WatchlistFanoutController.class, ServiceTokenFilter.class, TestSupportConfig.class);

  @Test
  void flagUnset_noFanoutBeans() {
    runner.run(
        ctx -> {
          assertThat(ctx).doesNotHaveBean(WatchlistFanoutController.class);
          assertThat(ctx).doesNotHaveBean(ServiceTokenFilter.class);
        });
  }

  @Test
  void fanoutFlagOn_bringsUpControllerAndAuthFilter() {
    runner
        .withPropertyValues("watchlist.fanout.enabled=true")
        .run(
            ctx -> {
              assertThat(ctx).hasSingleBean(WatchlistFanoutController.class);
              // The fan-out flag ALONE must also activate the bearer-gate filter — otherwise the
              // internal route is unauthenticated.
              assertThat(ctx).hasSingleBean(ServiceTokenFilter.class);
            });
  }

  /**
   * The service-token filter must gate the internal fan-out route: its
   * {@code @ConditionalOnExpression} has to include {@code watchlist.fanout.enabled}, or turning ON
   * only that flag would bring up the controller with no bearer gate.
   */
  @Test
  void serviceTokenFilterExpression_listsFanoutFlag() {
    ConditionalOnExpression ann =
        ServiceTokenFilter.class.getAnnotation(ConditionalOnExpression.class);
    assertThat(ann).as("ServiceTokenFilter must be @ConditionalOnExpression-gated").isNotNull();
    assertThat(ann.value()).contains("watchlist.fanout.enabled");
  }

  @Configuration
  static class TestSupportConfig {
    @Bean
    WorkflowClient workflowClient() {
      return Mockito.mock(WorkflowClient.class);
    }

    @Bean
    DSLContext dslContext() {
      return Mockito.mock(DSLContext.class);
    }

    @Bean
    TenantContext tenantContext() {
      return new TenantContext("dev", "copytrade-v1");
    }
  }
}
