package com.ohmytradeagent.marketdata.provider;

import java.math.BigDecimal;

/**
 * Issue #783: one option snapshot's model outputs — implied volatility plus the first-order greeks.
 * These are the UNRECOVERABLE fields the trade-context recorder snapshots at entry time (there is
 * no historical IV/greeks API to backfill from). Any field may be null when the provider omitted
 * it; a fully-empty snapshot is represented as an absent Optional, never an all-null record.
 */
public record OptionGreeks(
    BigDecimal impliedVolatility,
    BigDecimal delta,
    BigDecimal gamma,
    BigDecimal theta,
    BigDecimal vega) {}
