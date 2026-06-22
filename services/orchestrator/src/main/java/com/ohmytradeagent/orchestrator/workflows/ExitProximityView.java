package com.ohmytradeagent.orchestrator.workflows;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Dashboard query payload exposing how close an open watchlist-exit position is to its stop /
 * target / trail, in premium space. Returned by {@code exitProximity()} — observation-only; never
 * mutated by the query. As a query it does not append to workflow history, so it serves in-flight
 * workflows without {@code Workflow.getVersion} gating.
 *
 * <p>Raw levels only — distance percentages are computed consumer-side (BFF) so the query stays
 * deterministic (no clock read). Fields are null/false until the exit is armed ({@code
 * input.getTpRatio() != null}); a copytrade position that never arms the watchlist exit reports
 * {@code armed=false} with null levels.
 *
 * @param contractSymbol OCC option symbol of the open position
 * @param entryPremium per-contract entry basis, dollars (the breakeven anchor)
 * @param stopLevel current stop level in premium $ (moves to breakeven after the target fires)
 * @param targetLevel target level in premium $ (null before arm)
 * @param lastBid most recent evaluated exit price (live bid, or mid fallback when bid is null);
 *     null until the first exit tick
 * @param lastTickPremium most recent chandelier mid (null until the trail arms)
 * @param peakPremium ratcheting trail peak since arm (null when the trail is unarmed)
 * @param trailingArmed whether the chandelier trail is currently active
 * @param givebackPct configured trail giveback fraction (null when unarmed)
 * @param armed whether the watchlist exit is active on this position
 * @param lastTickAt timestamp of the most recent trail tick (null until the trail arms)
 */
public record ExitProximityView(
    String contractSymbol,
    BigDecimal entryPremium,
    BigDecimal stopLevel,
    BigDecimal targetLevel,
    BigDecimal lastBid,
    BigDecimal lastTickPremium,
    BigDecimal peakPremium,
    boolean trailingArmed,
    BigDecimal givebackPct,
    boolean armed,
    OffsetDateTime lastTickAt) {}
