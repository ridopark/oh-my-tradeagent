package com.ohmytradeagent.exec.fill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Placeholder {@link FillDispatcher} that logs each event but does not signal any workflow. Active
 * in Phase 1 of the fill-listener plan: the WebSocket transport ships first; Phase 2 replaces this
 * bean with a real journal-lookup + workflow-signal implementation. {@link
 * ConditionalOnMissingBean} makes the swap transparent — once {@code FillDispatcherImpl} is
 * present, this bean is not created.
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
