package com.ohmytradeagent.marketdata.recovery;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Transport mirror of the orchestrator's {@code positionState} query result — ONLY the two fields
 * the #776 boot recovery reads (the live payload carries 5). Same transport-mirror pattern and same
 * {@code ignoreUnknown} rationale as {@link ExitProximityViewMirror}; guarded by {@code
 * RecoveryViewDriftTest}.
 *
 * @param contractSymbol OCC option symbol of the open position
 * @param remainingQty contracts still open after any partial exits
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record PositionStateViewMirror(String contractSymbol, long remainingQty) {}
