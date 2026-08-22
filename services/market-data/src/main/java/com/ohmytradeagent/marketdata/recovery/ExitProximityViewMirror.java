package com.ohmytradeagent.marketdata.recovery;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Transport mirror of the orchestrator's {@code exitProximity} query result — ONLY the three fields
 * the #776 boot recovery reads. Market-data must not import orchestrator classes, so this is a
 * hand-written mirror on the BFF's transport-mirror pattern.
 *
 * <p>{@code ignoreUnknown} is load-bearing, not hygiene: the live payload carries 11 fields and
 * Temporal's data converter fails on unknown properties without it — the exact drift that once
 * dropped every position from the dashboard. Guarded by {@code RecoveryViewDriftTest}, which drives
 * the REAL {@code DefaultDataConverter}; every other recovery test mocks the stub query and would
 * survive the annotation's deletion.
 *
 * @param contractSymbol OCC option symbol of the open position
 * @param trailingArmed whether the chandelier/operator trail is armed
 * @param armed whether the watchlist exit is armed (the exit-only trail kind)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record ExitProximityViewMirror(String contractSymbol, boolean trailingArmed, boolean armed) {}
