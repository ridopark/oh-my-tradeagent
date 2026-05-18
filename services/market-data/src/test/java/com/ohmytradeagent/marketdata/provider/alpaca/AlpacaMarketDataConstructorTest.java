package com.ohmytradeagent.marketdata.provider.alpaca;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestClient;

/**
 * Regression guard for issue #56 item 1: {@link AlpacaMarketData} declares two constructors (a
 * 3-arg production constructor and a 5-arg package-private one used only by tests). With two
 * constructors and no {@code @Autowired} annotation, Spring's container fails to pick one and the
 * {@code market-data.provider=alpaca} bean cannot be instantiated. The fix is to annotate the 3-arg
 * constructor with {@code @Autowired}; this test fails at unit-test time if the annotation is ever
 * removed, so the regression is caught before pod-startup.
 */
class AlpacaMarketDataConstructorTest {

  @Test
  void productionConstructorIsAutowired() throws NoSuchMethodException {
    Constructor<AlpacaMarketData> ctor =
        AlpacaMarketData.class.getDeclaredConstructor(
            RestClient.class, ObjectMapper.class, AlpacaMarketDataProperties.class);
    assertThat(ctor.isAnnotationPresent(Autowired.class))
        .as(
            "AlpacaMarketData's 3-arg production constructor must be @Autowired so Spring can pick"
                + " it deterministically when the 5-arg test constructor is also present (issue"
                + " #56 item 1).")
        .isTrue();
  }
}
