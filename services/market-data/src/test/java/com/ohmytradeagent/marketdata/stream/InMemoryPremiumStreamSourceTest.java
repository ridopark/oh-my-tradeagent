package com.ohmytradeagent.marketdata.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.contract.PremiumTick;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the in-memory fan-out semantics of the Phase 4 stream source. The activity layer relies
 * on these properties: subscriptions fan to all listeners on a symbol; unsubscribe stops fan-out;
 * concurrent subscriptions on the same symbol both receive ticks.
 */
class InMemoryPremiumStreamSourceTest {

  private InMemoryPremiumStreamSource source;

  @BeforeEach
  void setUp() {
    source = new InMemoryPremiumStreamSource();
  }

  @Test
  void subscribeAndPush_fansOutToListener() {
    List<PremiumTick> received = new CopyOnWriteArrayList<>();
    Subscription sub = source.subscribe("NVDA  260516C00140000", "pos-1", received::add);

    assertThat(sub.subscriptionId()).isNotBlank();
    assertThat(sub.optionSymbol()).isEqualTo("NVDA  260516C00140000");
    assertThat(sub.positionWorkflowId()).isEqualTo("pos-1");

    source.pushTickForTest(
        "NVDA  260516C00140000",
        new BigDecimal("2.95"),
        OffsetDateTime.parse("2026-05-13T17:50:12Z"));

    assertThat(received).hasSize(1);
    assertThat(received.get(0).getPremium().doubleValue()).isEqualTo(2.95);
    assertThat(received.get(0).getContractSymbol()).isEqualTo("NVDA  260516C00140000");
  }

  @Test
  void unsubscribe_stopsFanOut() {
    List<PremiumTick> received = new ArrayList<>();
    Subscription sub = source.subscribe("AAPL  260516C00190000", "pos-2", received::add);

    source.unsubscribe(sub.subscriptionId());

    source.pushTickForTest(
        "AAPL  260516C00190000",
        new BigDecimal("1.20"),
        OffsetDateTime.parse("2026-05-13T17:51:00Z"));

    assertThat(received).isEmpty();
  }

  @Test
  void multipleSubscriptionsSameSymbol_bothReceive() {
    List<PremiumTick> rxA = new CopyOnWriteArrayList<>();
    List<PremiumTick> rxB = new CopyOnWriteArrayList<>();
    source.subscribe("NVDA  260516C00140000", "pos-A", rxA::add);
    source.subscribe("NVDA  260516C00140000", "pos-B", rxB::add);

    source.pushTickForTest(
        "NVDA  260516C00140000",
        new BigDecimal("3.10"),
        OffsetDateTime.parse("2026-05-13T17:55:00Z"));

    assertThat(rxA).hasSize(1);
    assertThat(rxB).hasSize(1);
  }

  @Test
  void pushTickForSymbolWithNoSubscribers_isNoOp() {
    // Sanity: no crash, no NPE.
    source.pushTickForTest(
        "TSLA  260516C00200000",
        new BigDecimal("0.50"),
        OffsetDateTime.parse("2026-05-13T17:52:00Z"));
  }
}
