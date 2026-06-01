package com.ohmytradeagent.tdbff.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.ohmytradeagent.tdbff.config.BrokerDataSourceRouter.BrokerNotConfiguredException;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

class BrokerDataSourceRouterTest {

  private final DSLContext execDsl = mock(DSLContext.class);
  private final BrokerDataSourceRouter router = new BrokerDataSourceRouter(execDsl);

  @Test
  void configuredTarget_returnsItsDsl() {
    assertThat(router.dslFor("alpaca-paper")).isSameAs(execDsl);
    assertThat(router.isConfigured("alpaca-paper")).isTrue();
  }

  @Test
  void unknownTarget_is404Mapped() {
    assertThat(router.isConfigured("tradier-live")).isFalse();
    assertThatThrownBy(() -> router.dslFor("tradier-live"))
        .isInstanceOf(BrokerNotConfiguredException.class);
  }

  @Test
  void nullTarget_is404Mapped() {
    assertThatThrownBy(() -> router.dslFor(null)).isInstanceOf(BrokerNotConfiguredException.class);
  }
}
