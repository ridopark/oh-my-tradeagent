package com.ohmytradeagent.marketdata.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.ohmytradeagent.marketdata.activities.SubscribeEquityActivityImpl;
import com.ohmytradeagent.marketdata.activities.SubscribePremiumActivityImpl;
import com.ohmytradeagent.marketdata.provider.MarketDataProvider;
import io.temporal.client.WorkflowClient;
import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TemporalWorkerConfigTest {

  /**
   * R1: the equity feed must fan out on its OWN dispatcher so a burst of equity ticks can't starve
   * copytrade's premium (chandelier) tick dispatch.
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

  /**
   * Validates the actual Spring wiring (not just bean existence): with both dispatcher beans
   * present, {@code SubscribeEquityActivityImpl} must receive the qualified {@code
   * equityTickDispatcher} and {@code SubscribePremiumActivityImpl} the {@code @Primary} {@code
   * tickDispatcher}. A typo'd qualifier or a future bean rename fails this test instead of only
   * failing at pod startup.
   */
  @Test
  void activities_areWiredToTheirRespectiveDispatchers() {
    ExecutorService premium = Executors.newSingleThreadExecutor();
    ExecutorService equity = Executors.newSingleThreadExecutor();
    ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    MarketDataProvider provider = mock(MarketDataProvider.class);
    WorkflowClient client = mock(WorkflowClient.class);
    try {
      new ApplicationContextRunner()
          .withBean(
              "tickDispatcher", ExecutorService.class, () -> premium, bd -> bd.setPrimary(true))
          .withBean("equityTickDispatcher", ExecutorService.class, () -> equity)
          .withBean("equityFeedWatchdog", ScheduledExecutorService.class, () -> watchdog)
          .withBean(MarketDataProvider.class, () -> provider)
          .withBean(WorkflowClient.class, () -> client)
          .withBean(SubscribeEquityActivityImpl.class)
          .withBean(SubscribePremiumActivityImpl.class)
          .run(
              ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(dispatcherOf(ctx.getBean(SubscribeEquityActivityImpl.class)))
                    .isSameAs(equity);
                assertThat(dispatcherOf(ctx.getBean(SubscribePremiumActivityImpl.class)))
                    .isSameAs(premium);
              });
    } finally {
      premium.shutdownNow();
      equity.shutdownNow();
      watchdog.shutdownNow();
    }
  }

  private static ExecutorService dispatcherOf(Object activity) throws Exception {
    Field f = activity.getClass().getDeclaredField("dispatcher");
    f.setAccessible(true);
    return (ExecutorService) f.get(activity);
  }
}
