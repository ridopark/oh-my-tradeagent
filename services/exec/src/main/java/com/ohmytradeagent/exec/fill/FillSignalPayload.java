package com.ohmytradeagent.exec.fill;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Wire shape sent to {@code CopytradeSignalWorkflow.onFill}. Field names match the orchestrator's
 * {@code FillEvent} record so Temporal's default Jackson data converter deserialises the JSON into
 * the receiver type without a shared interface dependency between the exec and orchestrator
 * modules.
 */
public record FillSignalPayload(
    String brokerOrderId, long filledQty, BigDecimal avgFillPrice, OffsetDateTime filledAt) {}
