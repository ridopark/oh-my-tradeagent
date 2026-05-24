package com.ohmytradeagent.exec.fill;

import com.ohmytradeagent.exec.broker.BrokerFillDetail;
import com.ohmytradeagent.exec.broker.BrokerOrderStatus;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import com.ohmytradeagent.exec.journal.JournaledOrder;
import com.ohmytradeagent.exec.journal.OrderIntentJournal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Safety-net poller for the fill listener. The WebSocket stream is the primary detector; this bean
 * exists so a wedged stream (silent disconnect, dropped events) does not strand a fill
 * indefinitely. Runs at a configurable cadence ({@code exec.fill-listener.poll.interval-ms},
 * default 30s), batches journal scans, and routes any broker-confirmed FILLED row through the
 * shared {@link FillDispatcher} (which dedupes against the WS path via the workflow's idempotent
 * {@code onFill} contract).
 *
 * <p>Rows newer than the grace window are skipped because the WS almost always wins inside that
 * window and polling them would waste broker rate budget.
 */
@Component
@ConditionalOnProperty(name = "exec.fill-listener.poll.enabled", havingValue = "true")
@EnableConfigurationProperties(FillPollerProperties.class)
public class FillPoller {

  private static final Logger log = LoggerFactory.getLogger(FillPoller.class);

  private final OrderIntentJournal journal;
  private final OptionsBroker broker;
  private final FillDispatcher dispatcher;
  private final FillListenerMetrics metrics;
  private final FillPollerProperties props;
  private final Clock clock;

  @Autowired
  public FillPoller(
      OrderIntentJournal journal,
      OptionsBroker broker,
      FillDispatcher dispatcher,
      FillListenerMetrics metrics,
      FillPollerProperties props) {
    this(journal, broker, dispatcher, metrics, props, Clock.systemUTC());
  }

  FillPoller(
      OrderIntentJournal journal,
      OptionsBroker broker,
      FillDispatcher dispatcher,
      FillListenerMetrics metrics,
      FillPollerProperties props,
      Clock clock) {
    this.journal = journal;
    this.broker = broker;
    this.dispatcher = dispatcher;
    this.metrics = metrics;
    this.props = props;
    this.clock = clock;
  }

  @Scheduled(
      fixedDelayString = "${exec.fill-listener.poll.interval-ms:30000}",
      initialDelayString = "${exec.fill-listener.poll.interval-ms:30000}")
  public void poll() {
    runOnce();
  }

  /** Visible for testing — exercises one polling cycle without the scheduler. */
  void runOnce() {
    OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(Duration.ofMillis(props.graceMs()));
    List<JournaledOrder> rows;
    try {
      rows = journal.findSubmittedOlderThan(cutoff, props.batchSize());
    } catch (RuntimeException e) {
      log.warn("fill-poller journal scan failed: {}", e.toString());
      metrics.recordPollScanFailure();
      return;
    }
    metrics.recordPollCycle();
    metrics.recordPollRowsScanned(rows.size());
    for (JournaledOrder row : rows) {
      checkRow(row);
    }
  }

  private void checkRow(JournaledOrder row) {
    // markSubmittedIfRecorded atomically sets state=SUBMITTED + broker_order_id, so a row matching
    // state='SUBMITTED' always carries a non-null broker_order_id. Defensive checks here would mask
    // an invariant break.
    BrokerOrderStatus status;
    try {
      status = broker.getOrderStatus(row.brokerOrderId());
    } catch (RuntimeException e) {
      log.warn(
          "fill-poller getOrderStatus failed broker_order_id={}: {}",
          row.brokerOrderId(),
          e.toString());
      return;
    }
    if (status != BrokerOrderStatus.FILLED) {
      return;
    }
    BrokerFillDetail detail;
    try {
      detail = broker.getFillDetail(row.brokerOrderId());
    } catch (RuntimeException e) {
      log.warn(
          "fill-poller getFillDetail failed broker_order_id={}: {}",
          row.brokerOrderId(),
          e.toString());
      return;
    }
    BrokerFillEvent fill =
        new BrokerFillEvent(
            row.brokerOrderId(),
            row.clientOrderId(),
            detail.filledQty(),
            detail.avgFillPrice(),
            detail.filledAt(),
            BrokerFillEvent.Source.POLL);
    metrics.recordPollFillDetected();
    try {
      dispatcher.dispatch(fill);
    } catch (RuntimeException e) {
      log.warn(
          "fill-poller dispatch failed broker_order_id={}: {}", row.brokerOrderId(), e.toString());
    }
  }
}
