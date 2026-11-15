package com.ohmytradeagent.marketdata.research;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.marketdata.provider.alpaca.AlpacaMarketDataProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The research beans are built by Spring through their production constructors only when {@code
 * market-data.provider=alpaca}, and are absent otherwise (CI/dev boot with {@code inmemory}).
 */
class ResearchWiringTest {

  @Configuration
  @EnableConfigurationProperties(AlpacaMarketDataProperties.class)
  @Import({AlpacaResearchClient.class, MarketResearchController.class})
  static class ResearchBeans {}

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
          .withUserConfiguration(ResearchBeans.class);

  @Test
  void alpacaProvider_createsBothBeans() {
    runner
        .withPropertyValues(
            "market-data.provider=alpaca",
            "market-data.alpaca.data-base-url=https://data.example.invalid",
            "market-data.alpaca.api-key-id=key-id-for-test",
            "market-data.alpaca.api-secret-key=key-secret-for-test")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(AlpacaResearchClient.class);
              assertThat(context).hasSingleBean(MarketResearchController.class);
            });
  }

  @Test
  void inmemoryProvider_createsNeither() {
    runner
        .withPropertyValues("market-data.provider=inmemory")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(AlpacaResearchClient.class);
              assertThat(context).doesNotHaveBean(MarketResearchController.class);
            });
  }
}
