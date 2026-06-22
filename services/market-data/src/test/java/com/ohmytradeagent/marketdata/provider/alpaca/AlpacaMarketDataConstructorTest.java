package com.ohmytradeagent.marketdata.provider.alpaca;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.marketdata.health.FeedHealth;
import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestClient;

/**
 * Regression guard for issue #56 item 1: {@link AlpacaMarketData} declares multiple constructors (a
 * production one Spring autowires and package-private ones used only by tests). With several
 * constructors and no {@code @Autowired} annotation, Spring's container fails to pick one and the
 * {@code market-data.provider=alpaca} bean cannot be instantiated. The fix is to annotate the
 * production constructor with {@code @Autowired}; this test fails at unit-test time if the
 * annotation is ever removed, so the regression is caught before pod-startup.
 */
class AlpacaMarketDataConstructorTest {

  @Test
  void productionConstructorIsAutowired() throws NoSuchMethodException {
    Constructor<AlpacaMarketData> ctor =
        AlpacaMarketData.class.getDeclaredConstructor(
            RestClient.class,
            ObjectMapper.class,
            AlpacaMarketDataProperties.class,
            FeedHealth.class);
    assertThat(ctor.isAnnotationPresent(Autowired.class))
        .as(
            "AlpacaMarketData's production constructor must be @Autowired so Spring can pick it"
                + " deterministically when the package-private test constructors are also present"
                + " (issue #56 item 1).")
        .isTrue();
  }
}
