package com.ohmytradeagent.marketdata.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;

class TemporalWorkerConfigTest {

  /**
   * R1: the equity feed must fan out on its OWN dispatcher so a burst of equity ticks can't starve
   * copytrade's premium (chandelier) tick dispatch. This asserts the two beans are distinct; the
   * {@code @Qualifier("equityTickDispatcher")} wiring in {@code SubscribeEquityActivityImpl} is
   * validated at context startup.
   */
  @Test
  void equityAndPremiumDispatchers_areDistinctInstances() {
    TemporalWorkerConfig cfg = new TemporalWorkerConfig();
    ExecutorService premium = cfg.tickDispatcher();
    ExecutorService equity = cfg.equityTickDispatcher();
    try {
      assertThat(equity).isNotSameAs(premium);
    } finally {
      premium.shutdownNow();
      equity.shutdownNow();
    }
  }
}
