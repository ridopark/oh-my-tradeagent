package com.ohmytradeagent.marketdata.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.marketdata.health.FeedHealth.Feed;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FeedHealthTest {

  @Test
  void initialState_disconnected_noTick() {
    FeedHealth fh = new FeedHealth(new SimpleMeterRegistry());
    assertThat(fh.connected(Feed.EQUITY)).isFalse();
    assertThat(fh.connected(Feed.OPTION)).isFalse();
    assertThat(fh.lastTickAgeMillis(Feed.EQUITY)).isEqualTo(FeedHealth.NO_TICK);
    assertThat(fh.lastTickAgeMillis(Feed.OPTION)).isEqualTo(FeedHealth.NO_TICK);
  }

  @Test
  void markConnectedAndDisconnected_flipConnectedFlag() {
    FeedHealth fh = new FeedHealth(new SimpleMeterRegistry());
    fh.markConnected(Feed.OPTION);
    assertThat(fh.connected(Feed.OPTION)).isTrue();
    fh.markDisconnected(Feed.OPTION);
    assertThat(fh.connected(Feed.OPTION)).isFalse();
  }

  @Test
  void recordTick_marksConnectedAndSetsAge() {
    FeedHealth fh = new FeedHealth(new SimpleMeterRegistry());
    fh.recordTick(Feed.EQUITY);
    assertThat(fh.connected(Feed.EQUITY)).isTrue();
    assertThat(fh.lastTickAgeMillis(Feed.EQUITY)).isBetween(0L, 5_000L);
    // The other feed is untouched.
    assertThat(fh.lastTickAgeMillis(Feed.OPTION)).isEqualTo(FeedHealth.NO_TICK);
  }

  @Test
  void gauges_registeredPerFeed_reflectState() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    FeedHealth fh = new FeedHealth(registry);

    fh.markConnected(Feed.EQUITY);
    fh.recordTick(Feed.OPTION);

    assertThat(registry.get("omo_feed_connected").tag("feed", "equity").gauge().value())
        .isEqualTo(1d);
    assertThat(registry.get("omo_feed_connected").tag("feed", "option").gauge().value())
        .isEqualTo(1d);
    // last-tick-age gauge: option has ticked (>=0), equity has not (-1 sentinel).
    assertThat(registry.get("omo_feed_last_tick_age_ms").tag("feed", "option").gauge().value())
        .isGreaterThanOrEqualTo(0d);
    assertThat(registry.get("omo_feed_last_tick_age_ms").tag("feed", "equity").gauge().value())
        .isEqualTo((double) FeedHealth.NO_TICK);
  }

  @Test
  void endpoint_exposesPerFeedConnectedAndAge() {
    FeedHealth fh = new FeedHealth(new SimpleMeterRegistry());
    fh.recordTick(Feed.OPTION);
    fh.markConnected(Feed.EQUITY);

    Map<String, Object> out = new FeedHealthEndpoint(fh).feedHealth();

    assertThat(out).containsOnlyKeys("equity", "option");
    @SuppressWarnings("unchecked")
    Map<String, Object> option = (Map<String, Object>) out.get("option");
    assertThat(option.get("connected")).isEqualTo(true);
    assertThat((long) option.get("lastTickAgeMs")).isBetween(0L, 5_000L);
    @SuppressWarnings("unchecked")
    Map<String, Object> equity = (Map<String, Object>) out.get("equity");
    assertThat(equity.get("connected")).isEqualTo(true);
    assertThat((long) equity.get("lastTickAgeMs")).isEqualTo(FeedHealth.NO_TICK);
  }
}
