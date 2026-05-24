package com.ohmytradeagent.exec.fill;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * In-process representation of one broker-reported fill, normalised away from any specific broker's
 * wire shape. Produced by {@link AlpacaTradeUpdatesStream} (push, {@link Source#WS}) and the
 * polling fallback (pull, {@link Source#POLL}); consumed by {@link FillDispatcher}.
 */
public record BrokerFillEvent(
    String brokerOrderId,
    String clientOrderId,
    long filledQty,
    BigDecimal avgFillPrice,
    OffsetDateTime filledAt,
    Source source) {

  public enum Source {
    WS,
    POLL
  }
}
