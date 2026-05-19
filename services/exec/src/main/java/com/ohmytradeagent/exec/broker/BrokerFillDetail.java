package com.ohmytradeagent.exec.broker;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Broker-confirmed fill detail returned by {@link OptionsBroker#getFillDetail(String)}.
 *
 * <p>Issue #165: captured when a cancel attempt races a fill so the journal can be reconciled to
 * FILLED with the broker's view of qty / avg price / fill time. Not a contract DTO — purely
 * internal to the exec sidecar.
 */
public record BrokerFillDetail(long filledQty, BigDecimal avgFillPrice, OffsetDateTime filledAt) {}
