package com.ohmytradeagent.orchestrator.workflows;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record FillEvent(
    String brokerOrderId, long filledQty, BigDecimal avgFillPrice, OffsetDateTime filledAt) {}
