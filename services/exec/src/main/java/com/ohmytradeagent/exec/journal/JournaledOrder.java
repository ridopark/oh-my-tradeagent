package com.ohmytradeagent.exec.journal;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Snapshot of one {@code order_intent_journal} row.
 *
 * <p>Issue #165: {@code filledQty}, {@code avgFillPrice}, and {@code filledAt} are populated only
 * for FILLED rows. For all other states they are {@code null}.
 */
public record JournaledOrder(
    String intentKey,
    String signalId,
    String tenantId,
    String strategyId,
    String brokerTarget,
    String clientOrderId,
    String optionSymbol,
    String side,
    long qty,
    BigDecimal limitPrice,
    OrderState state,
    String brokerOrderId,
    OffsetDateTime recordedAt,
    OffsetDateTime submittedAt,
    OffsetDateTime lastStateAt,
    OffsetDateTime cancelAttemptedAt,
    String lastError,
    Long filledQty,
    BigDecimal avgFillPrice,
    OffsetDateTime filledAt,
    long version) {}
