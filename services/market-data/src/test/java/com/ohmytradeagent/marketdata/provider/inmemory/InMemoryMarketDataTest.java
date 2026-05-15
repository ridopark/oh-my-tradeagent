package com.ohmytradeagent.marketdata.provider.inmemory;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.marketdata.provider.Subscription;
import com.ohmytradeagent.marketdata.provider.Tick;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the in-memory fan-out semantics that {@link SubscribePremiumActivityImpl} relies on:
 * subscriptions fan to all listeners on a symbol; close() detaches one listener without affecting
 * the others; concurrent subscriptions on the same symbol both receive ticks.
 */
class InMemoryMarketDataTest {

  private InMemoryMarketData source;

  @BeforeEach
  void setUp() {
    source = new InMemoryMarketData();
  }

  @Test
  void subscribeAndPush_fansOutToListener() {
    List<Tick> received = new CopyOnWriteArrayList<>();
    Subscription sub = source.subscribePremium("NVDA  260516C00140000", received::add);

    assertThat(sub.subscriptionId()).isNotBlank();

    source.pushTickForTest(
        "NVDA  260516C00140000",
        new BigDecimal("2.95"),
        OffsetDateTime.parse("2026-05-13T17:50:12Z"));

    assertThat(received).hasSize(1);
    assertThat(received.get(0).premium().doubleValue()).isEqualTo(2.95);
    assertThat(received.get(0).occSymbol()).isEqualTo("NVDA  260516C00140000");
  }

  @Test
  void close_stopsFanOut() {
    List<Tick> received = new ArrayList<>();
    Subscription sub = source.subscribePremium("AAPL  260516C00190000", received::add);

    sub.close();

    source.pushTickForTest(
        "AAPL  260516C00190000",
        new BigDecimal("1.20"),
        OffsetDateTime.parse("2026-05-13T17:51:00Z"));

    assertThat(received).isEmpty();
  }

  @Test
  void multipleSubscriptionsSameSymbol_bothReceive() {
    List<Tick> rxA = new CopyOnWriteArrayList<>();
    List<Tick> rxB = new CopyOnWriteArrayList<>();
    source.subscribePremium("NVDA  260516C00140000", rxA::add);
    source.subscribePremium("NVDA  260516C00140000", rxB::add);

    source.pushTickForTest(
        "NVDA  260516C00140000",
        new BigDecimal("3.10"),
        OffsetDateTime.parse("2026-05-13T17:55:00Z"));

    assertThat(rxA).hasSize(1);
    assertThat(rxB).hasSize(1);
  }

  @Test
  void closeOnOneListener_otherStillReceives() {
    List<Tick> rxA = new CopyOnWriteArrayList<>();
    List<Tick> rxB = new CopyOnWriteArrayList<>();
    Subscription subA = source.subscribePremium("NVDA  260516C00140000", rxA::add);
    source.subscribePremium("NVDA  260516C00140000", rxB::add);

    subA.close();

    source.pushTickForTest(
        "NVDA  260516C00140000",
        new BigDecimal("3.20"),
        OffsetDateTime.parse("2026-05-13T17:56:00Z"));

    assertThat(rxA).isEmpty();
    assertThat(rxB).hasSize(1);
  }

  @Test
  void pushTickForSymbolWithNoSubscribers_isNoOp() {
    source.pushTickForTest(
        "TSLA  260516C00200000",
        new BigDecimal("0.50"),
        OffsetDateTime.parse("2026-05-13T17:52:00Z"));
  }
}
