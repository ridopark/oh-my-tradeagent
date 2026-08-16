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
 * @param lastTickAt the QUOTE's own timestamp on the most recent tick — how fresh the market data
 *     was, NOT when we heard it. Stamped only while the trail is armed, because {@code processTick}
 *     early-returns on {@code !trailingArmed}. Null when no ticks yet.
 * @param lastTickObservedAt WORKFLOW-CLOCK time at which a tick was last drained, stamped before
 *     the watchlist/copytrade route fork and regardless of whether anything is armed. This is the
 *     field a staleness check needs: {@code lastTickAt} is blank exactly when a position is
 *     unarmed, and quote time answers a different question. The gap between the two is itself
 *     diagnostic — a large one means we are being fed stale quotes promptly. Null when no tick has
 *     ever arrived.
 *     <p><b>Compare it against WALL-CLOCK now, not against a workflow timestamp.</b> The workflow
 *     clock only advances on workflow tasks, so a genuinely silent workflow's own notion of "now"
 *     freezes — deriving an age inside the workflow would report a constant and fail at precisely
 *     the job. The caller holds real time; let it do the subtraction.
 * @param ticksReceived count of ticks consumed by the workflow since arm
 */
public record TrailingState(
    boolean armed,
    BigDecimal peakPremium,
    BigDecimal givebackPct,
    BigDecimal thresholdPremium,
    BigDecimal lastTickPremium,
    OffsetDateTime lastTickAt,
    OffsetDateTime lastTickObservedAt,
    long ticksReceived) {}
