package com.ohmytradeagent.exectradierpaper.journal;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

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
    long version) {}
