package com.ohmytradeagent.orchestrator.workflows;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Phase 4 query-result payload exposing the chandelier-trail internal state on PositionWorkflow.
 * Returned by {@code trailingState()} — observation-only; never mutated by the query.
 *
 * @param armed whether the trail is currently active
 * @param peakPremium ratcheting peak premium since arm (null when unarmed)
 * @param givebackPct configured giveback fraction (null when unarmed)
 * @param thresholdPremium {@code peak * (1 - giveback_pct)} — the fire trigger (null when unarmed)
 * @param lastTickPremium most recent tick observed (null when no ticks yet)
 * @param lastTickAt timestamp of most recent tick (null when no ticks yet)
 * @param ticksReceived count of ticks consumed by the workflow since arm
 */
public record TrailingState(
    boolean armed,
    BigDecimal peakPremium,
    BigDecimal givebackPct,
    BigDecimal thresholdPremium,
    BigDecimal lastTickPremium,
    OffsetDateTime lastTickAt,
    long ticksReceived) {}
