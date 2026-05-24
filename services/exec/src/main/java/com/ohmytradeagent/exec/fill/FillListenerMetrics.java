package com.ohmytradeagent.exec.fill;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Centralised Micrometer instrumentation for the fill listener. Counters are registered eagerly so
 * a Prometheus scrape that arrives before the first fill still sees the series — silent-zero is
 * operationally clearer than no-series-at-all when triaging "is the listener up?".
 *
 * <p>{@code fill_listener.last_event_age_seconds} is a derived gauge over the most-recent event
 * timestamp; {@link #markEvent()} bumps the clock without taking a meter lock.
 */
@Component
public class FillListenerMetrics {

  private final Clock clock;
  private final AtomicLong lastEventEpochMs = new AtomicLong(0L);
  private final Map<String, Counter> receivedByEvent;
  private final Counter eventsDispatched;
  private final Counter eventsDroppedDedup;
  private final Counter reconnects;
  private final Counter eventsUnknownOrder;
  private final Counter signalWorkflowNotFound;
  private final Counter signalErrors;
  private final Counter pollCycles;
  private final Counter pollRowsScanned;
  private final Counter pollFillsDetected;
  private final Counter pollScanFailures;

  public FillListenerMetrics(MeterRegistry registry) {
    this(registry, Clock.systemUTC());
  }

  FillListenerMetrics(MeterRegistry registry, Clock clock) {
    this.clock = clock;
    Map<String, Counter> received = new LinkedHashMap<>();
    for (String event : new String[] {"fill", "partial_fill"}) {
      received.put(
          event,
          Counter.builder("fill_listener.events_received")
              .tag("event", event)
              .description("Trade-update events received from the broker stream.")
              .register(registry));
    }
    this.receivedByEvent = Map.copyOf(received);
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
    this.eventsUnknownOrder =
        Counter.builder("fill_listener.events_unknown_order")
            .description("Fills whose broker_order_id has no matching journal row.")
            .register(registry);
    this.signalWorkflowNotFound =
        Counter.builder("fill_listener.signal_workflow_not_found")
            .description("Workflow already completed when the signal arrived (benign).")
            .register(registry);
    this.signalErrors =
        Counter.builder("fill_listener.signal_errors")
            .description("Non-NOT_FOUND Temporal failures while sending the onFill signal.")
            .register(registry);
    this.pollCycles =
        Counter.builder("fill_listener.poll_cycles")
            .description("Polling fallback cycles completed.")
            .register(registry);
    this.pollRowsScanned =
        Counter.builder("fill_listener.poll_rows_scanned")
            .description("SUBMITTED rows examined by the polling fallback (sum across cycles).")
            .register(registry);
    this.pollFillsDetected =
        Counter.builder("fill_listener.poll_fills_detected")
            .description(
                "Polled rows the broker reported as FILLED (routed through FillDispatcher).")
            .register(registry);
    this.pollScanFailures =
        Counter.builder("fill_listener.poll_scan_failures")
            .description(
                "Polling cycles where the journal scan threw — distinguishes broken DB from empty"
                    + " journal in Grafana.")
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
    Counter counter = receivedByEvent.get(event);
    if (counter != null) {
      counter.increment();
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

  public void recordUnknownOrder() {
    eventsUnknownOrder.increment();
  }

  public void recordSignalWorkflowNotFound() {
    signalWorkflowNotFound.increment();
  }

  public void recordSignalError() {
    signalErrors.increment();
  }

  public void recordPollCycle() {
    pollCycles.increment();
  }

  public void recordPollRowsScanned(long n) {
    pollRowsScanned.increment(n);
  }

  public void recordPollFillDetected() {
    pollFillsDetected.increment();
  }

  public void recordPollScanFailure() {
    pollScanFailures.increment();
  }

  public void markEvent() {
    lastEventEpochMs.set(clock.millis());
  }

  Instant lastEvent() {
    long t = lastEventEpochMs.get();
    return t == 0L ? null : Instant.ofEpochMilli(t);
  }
}
