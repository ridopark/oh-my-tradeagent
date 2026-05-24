package com.ohmytradeagent.exec.fill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Fallback {@link FillDispatcher} active only when no other {@code FillDispatcher} bean is
 * registered. Logs each event but does not signal any workflow — used when the listener is enabled
 * but the resolver-to-workflow path is not yet wired (e.g. integration testing the transport
 * without a Temporal cluster).
 */
@Component
@ConditionalOnMissingBean(FillDispatcher.class)
public class NoopFillDispatcher implements FillDispatcher {

  private static final Logger log = LoggerFactory.getLogger(NoopFillDispatcher.class);

  @Override
  public void dispatch(BrokerFillEvent event) {
    log.info(
        "noop dispatch broker_order_id={} qty={} avg_px={} source={}",
        event.brokerOrderId(),
        event.filledQty(),
        event.avgFillPrice(),
        event.source());
  }
}
