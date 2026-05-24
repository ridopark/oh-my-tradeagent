package com.ohmytradeagent.exec.fill;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Centralised Micrometer instrumentation for the fill listener. Counters are registered eagerly
 * (zero-valued at startup) so a Prometheus scrape that arrives before the first fill still sees the
 * series — silent-zero is operationally clearer than no-series-at-all when triaging "is the
 * listener up?"
 *
 * <p>{@code fill_listener.last_event_age_seconds} is a derived gauge over the most-recent event
 * timestamp; the bean exposes {@link #markEvent()} so the listener bumps the clock from its own
 * thread without taking a meter lock.
 */
@Component
public class FillListenerMetrics {

  private final Clock clock;
  private final AtomicLong lastEventEpochMs = new AtomicLong(0L);
  private final Counter eventsReceivedFill;
  private final Counter eventsReceivedPartial;
  private final Counter eventsDispatched;
  private final Counter eventsDroppedDedup;
  private final Counter reconnects;

  public FillListenerMetrics(MeterRegistry registry) {
    this(registry, Clock.systemUTC());
  }

  FillListenerMetrics(MeterRegistry registry, Clock clock) {
    this.clock = clock;
    this.eventsReceivedFill =
        Counter.builder("fill_listener.events_received")
            .tag("event", "fill")
            .description("Trade-update events received from the broker stream.")
            .register(registry);
    this.eventsReceivedPartial =
        Counter.builder("fill_listener.events_received")
            .tag("event", "partial_fill")
            .description("Trade-update events received from the broker stream.")
            .register(registry);
    this.eventsDispatched =
        Counter.builder("fill_listener.events_dispatched")
            .description("Events handed to FillDispatcher after filter + dedup.")
            .register(registry);
    this.eventsDroppedDedup =
        Counter.builder("fill_listener.events_dropped_dedup")
            .description("Events suppressed because (broker_order_id, filled_qty) was seen.")
            .register(registry);
    this.reconnects =
        Counter.builder("fill_listener.reconnects")
            .description("WebSocket reconnect attempts (incremented when the socket re-opens).")
            .register(registry);
    Gauge.builder(
            "fill_listener.last_event_age_seconds",
            lastEventEpochMs,
            v -> {
              long t = v.get();
              if (t == 0L) {
                return Double.POSITIVE_INFINITY;
              }
              return (clock.millis() - t) / 1000.0;
            })
        .description(
            "Seconds since the most recent trade-update event was received. +Inf before first event.")
        .register(registry);
  }

  public void recordReceived(String event) {
    if ("fill".equals(event)) {
      eventsReceivedFill.increment();
    } else if ("partial_fill".equals(event)) {
      eventsReceivedPartial.increment();
    }
  }

  public void recordDispatched() {
    eventsDispatched.increment();
  }

  public void recordDroppedDedup() {
    eventsDroppedDedup.increment();
  }

  public void recordReconnect() {
    reconnects.increment();
  }

  public void markEvent() {
    lastEventEpochMs.set(clock.millis());
  }

  Instant lastEvent() {
    long t = lastEventEpochMs.get();
    return t == 0L ? null : Instant.ofEpochMilli(t);
  }
}
