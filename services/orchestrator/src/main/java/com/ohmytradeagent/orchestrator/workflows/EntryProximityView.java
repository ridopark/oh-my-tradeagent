package com.ohmytradeagent.orchestrator.workflows;

import java.math.BigDecimal;

/**
 * Dashboard query payload exposing how close a watchlist-trigger leg is to firing: the trigger
 * level, its band, the most recent observed underlying price, and the {@link
 * com.ohmytradeagent.orchestrator.domain.EntryStateMachine} state. Returned by {@code
 * entryProximity()} — observation-only; never mutated by the query.
 *
 * <p>Raw levels only — distance percentages are computed consumer-side (BFF) so the query stays
 * deterministic (no clock read). {@code lastPrice} is null until the first non-stale tick seeds the
 * machine. Before the workflow method assigns the machine, {@code state} reports {@code
 * INITIALIZING}.
 *
 * @param ticker underlying symbol of the leg
 * @param direction {@code ABOVE} (breakout/call) or {@code BELOW} (breakdown/put)
 * @param triggerLevel the trigger price {@code T}
 * @param bandLow {@code T*(1-g)} band edge
 * @param bandHigh {@code T*(1+g)} band edge
 * @param lastPrice most recent non-stale underlying price observed (null before the first tick)
 * @param state machine state: {@code ARMED} | {@code BROKEN_OUT} | {@code FIRED} | {@code SKIPPED}
 *     | {@code INITIALIZING}
 */
public record EntryProximityView(
    String ticker,
    String direction,
    BigDecimal triggerLevel,
    BigDecimal bandLow,
    BigDecimal bandHigh,
    BigDecimal lastPrice,
    String state) {}
