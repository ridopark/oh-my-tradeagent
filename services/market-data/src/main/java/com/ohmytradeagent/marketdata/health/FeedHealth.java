package com.ohmytradeagent.marketdata.health;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Shared, thread-safe liveness state for the market-data WS feeds (equity + option). The provider
 * ({@code AlpacaMarketData}) marks connect/disconnect and records each parsed tick; both the
 * Micrometer gauges (registered here) and the {@code /actuator/feedhealth} endpoint read it.
 *
 * <p>Always present (no provider gate) so the endpoint/gauges exist even when {@code
 * market-data.provider=inmemory} — in that case nothing marks the feeds and they honestly report
 * disconnected / no-tick.
 *
 * <p>Gauges: {@code omo_feed_connected{feed}} (1/0) and {@code omo_feed_last_tick_age_ms{feed}}
 * (millis since the last tick, or -1 before the first tick).
 */
@Component
public class FeedHealth {

  /** Which WS endpoint. Two fixed feeds (one connection each); see AlpacaMarketData. */
  public enum Feed {
    EQUITY,
    OPTION
  }

  /** Millis-since-last-tick sentinel reported before any tick has arrived for a feed. */
  public static final long NO_TICK = -1L;

  private static final class FeedState {
    volatile boolean connected;
    volatile long lastTickMillis; // 0 = no tick yet
  }

  private final Map<Feed, FeedState> states = new EnumMap<>(Feed.class);

  public FeedHealth(MeterRegistry registry) {
    for (Feed feed : Feed.values()) {
      FeedState state = new FeedState();
      states.put(feed, state);
      String tag = feed.name().toLowerCase();
      Gauge.builder("omo_feed_connected", state, s -> s.connected ? 1d : 0d)
          .tag("feed", tag)
          .description("1 when the market-data WS feed is connected, else 0")
          .register(registry);
      Gauge.builder("omo_feed_last_tick_age_ms", state, s -> ageMillis(s))
          .tag("feed", tag)
          .description("Millis since the last tick on the feed (-1 before the first tick)")
          .register(registry);
    }
  }

  public void markConnected(Feed feed) {
    states.get(feed).connected = true;
  }

  public void markDisconnected(Feed feed) {
    states.get(feed).connected = false;
  }

  /** A parsed tick arrived: refresh the timestamp and treat the feed as connected. */
  public void recordTick(Feed feed) {
    FeedState state = states.get(feed);
    state.lastTickMillis = System.currentTimeMillis();
    state.connected = true;
  }

  public boolean connected(Feed feed) {
    return states.get(feed).connected;
  }

  /** Millis since the last tick, or {@link #NO_TICK} before the first tick. */
  public long lastTickAgeMillis(Feed feed) {
    return ageMillis(states.get(feed));
  }

  private static long ageMillis(FeedState state) {
    long last = state.lastTickMillis;
    return last == 0L ? NO_TICK : System.currentTimeMillis() - last;
  }
}
