package com.ohmytradeagent.exec.fill;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
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
  private final Counter subscriptionConfirmed;
  private final Counter framesWithoutStream;
  private final Map<String, Counter> wsCallbacksByChannel;
  private final Map<String, Counter> wsFragmentsByChannel;
  private final MeterRegistry registry;
  private final Map<String, AtomicLong> partialBytesByTenant = new ConcurrentHashMap<>();
  private final Counter eventsDroppedDedup;
  private final Counter reconnects;
  private final Counter recycles;
  private final Counter eventsUnknownOrder;
  private final Counter signalWorkflowNotFound;
  private final Counter signalErrors;
  private final Counter pollCycles;
  private final Counter pollRowsScanned;
  private final Counter pollFillsDetected;
  private final Counter pollScanFailures;

  @Autowired
  public FillListenerMetrics(MeterRegistry registry) {
    this(registry, Clock.systemUTC());
  }

  FillListenerMetrics(MeterRegistry registry, Clock clock) {
    this.clock = clock;
    this.registry = registry;
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
    // #715: instrument the WebSocket callback boundary itself, BELOW every other counter here.
    //
    // Every existing signal in this class sits behind handleFrame, so all of them read zero for
    // three completely different faults, and they cannot be told apart:
    //   1. bytes arrive fragmented and `last` never becomes true -> we accumulate forever and
    //      never call handleFrame (no log, no counter, no reconnect: the abort is at 1MB)
    //   2. complete frames arrive but our dispatch is broken
    //   3. nothing ever reaches the client
    //
    // Only (3) exonerates this client. Counting the callbacks separates them:
    //   callbacks > 0, fragments > 0  -> (1), and it is OURS
    //   callbacks > 0, fragments == 0 -> (2), and it is OURS
    //   callbacks == 0               -> (3): for bytes to have arrived, the JDK would have to be
    //                                   discarding DECRYPTED frames below the callback boundary
    //
    // That last row is why this is worth six lines: it settles whether the loss is client-side
    // without a packet capture, a veth, an idle baseline, or operator root.
    Map<String, Counter> callbacks = new LinkedHashMap<>();
    Map<String, Counter> fragments = new LinkedHashMap<>();
    for (String channel : new String[] {"text", "binary"}) {
      callbacks.put(
          channel,
          Counter.builder("fill_listener.ws_callbacks")
              .tag("channel", channel)
              .description(
                  "WebSocket Listener callback invocations. Counted on ENTRY, before"
                      + " fragment accumulation and before handleFrame, so this is the lowest"
                      + " observable point in the client. Zero across a session with known order"
                      + " activity means nothing reached the client above TLS.")
              .register(registry));
      fragments.put(
          channel,
          Counter.builder("fill_listener.ws_fragments")
              .tag("channel", channel)
              .description(
                  "Callback invocations carrying a NON-final fragment (last == false). Non-zero"
                      + " while events_received stays zero means frames are arriving but never"
                      + " completing, so handleFrame is never reached and every other counter here"
                      + " reads zero for a reason that is ours, not the broker's.")
              .register(registry));
    }
    this.wsCallbacksByChannel = Map.copyOf(callbacks);
    this.wsFragmentsByChannel = Map.copyOf(fragments);
    this.eventsDispatched =
        Counter.builder("fill_listener.events_dispatched")
            .description(
                "Events successfully signalled to the target workflow (post journal-lookup). "
                    + "Bumped inside FillDispatcherImpl so WS and poll paths share one count.")
            .register(registry);
    this.subscriptionConfirmed =
        Counter.builder("fill_listener.subscription_confirmed")
            .description(
                "Sockets that received a `listening` ack naming trade_updates. The POSITIVE"
                    + " evidence a subscription exists (#715) — a socket can authenticate"
                    + " successfully and still never subscribe, and this counter is the only"
                    + " signal that distinguishes the two. Expect one per open socket per"
                    + " (re)connect, so a pod serving N tenants should reach N shortly after boot.")
            .register(registry);
    // #715: a deliberate recycle also increments `reconnects`, which would destroy
    // `reconnects_total == 0` as the starved-socket tell — the exact signal that cracked this
    // case. Counting recycles separately keeps (reconnects - recycles) as genuine broker-side
    // disconnects.
    this.recycles =
        Counter.builder("fill_listener.recycles")
            .description(
                "Sockets deliberately torn down after reaching their configured maximum lifetime"
                    + " (#715). Subtract from reconnects_total to isolate genuine broker-side"
                    + " disconnects.")
            .register(registry);
    this.framesWithoutStream =
        Counter.builder("fill_listener.frames_without_stream")
            .description(
                "Frames carrying no top-level `stream` field, i.e. an envelope this listener does"
                    + " not model. Non-zero means trade updates may be arriving in a shape that is"
                    + " being discarded — see the accompanying WARN for the field names. Counted"
                    + " on every occurrence even though the log is damped, so volume stays"
                    + " visible.")
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

  /**
   * Bytes currently sitting in this tenant's un-dispatched fragment accumulator.
   *
   * <p>#715: `handleFrame` is only reached when a frame's FINAL fragment arrives. If frames arrive
   * fragmented and {@code last} never becomes true, both handlers accumulate, re-arm demand, and
   * never dispatch — and because every other counter in this class sits behind `handleFrame`, ALL
   * of them read zero. So does the reconnect counter, since the oversize abort is at 1MB and a
   * session's worth of fragments is kilobytes. That failure is invisible by construction; this
   * gauge is the only thing that would show it.
   *
   * <p>Non-zero and rising while {@code events_received} stays zero means the bytes are arriving
   * and WE are failing to complete them — the fault is ours, not the broker's.
   *
   * <p>Reported per tenant because each socket owns its own accumulator, and summed across the two
   * channels: RFC 6455 forbids interleaving a text and a binary message on one connection, so at
   * most one is ever non-empty and the sum is exact rather than an approximation.
   */
  public void recordPartialBytes(String tenant, long bytes) {
    partialBytesByTenant
        .computeIfAbsent(
            tenant,
            t -> {
              AtomicLong holder = new AtomicLong();
              Gauge.builder("fill_listener.ws_partial_bytes", holder, AtomicLong::doubleValue)
                  .tag("tenant", t)
                  .description(
                      "Bytes buffered in the un-dispatched WebSocket fragment accumulator. Non-zero"
                          + " while events_received stays zero means frames are arriving but never"
                          + " completing, so handleFrame is never reached and every other"
                          + " fill_listener counter reads zero for a client-side reason.")
                  .register(registry);
              return holder;
            })
        .set(bytes);
  }

  /** Called on ENTRY to onText/onBinary — the lowest observable point in the client. */
  public void recordWsCallback(String channel, boolean last) {
    Counter c = wsCallbacksByChannel.get(channel);
    if (c != null) {
      c.increment();
    }
    if (!last) {
      Counter f = wsFragmentsByChannel.get(channel);
      if (f != null) {
        f.increment();
      }
    }
  }

  public void recordDispatched() {
    eventsDispatched.increment();
  }

  public void recordRecycle() {
    recycles.increment();
  }

  public void recordSubscriptionConfirmed() {
    subscriptionConfirmed.increment();
  }

  public void recordFrameWithoutStream() {
    framesWithoutStream.increment();
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
