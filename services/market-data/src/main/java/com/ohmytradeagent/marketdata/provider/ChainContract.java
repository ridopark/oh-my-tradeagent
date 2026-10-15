package com.ohmytradeagent.marketdata.provider;

import java.math.BigDecimal;

/**
 * One row of an option chain for the /live chain picker: the contract (COMPACT OCC + strike) plus
 * its latest NBBO, IV, delta and today's volume. Every market field may be null when the provider
 * omitted it (an illiquid strike with no quote is still a listed contract). Display-only — the
 * manual-entry confirm step re-quotes the picked contract before anything is ordered.
 */
public record ChainContract(
    String occ,
    BigDecimal strike,
    BigDecimal bid,
    BigDecimal mid,
    BigDecimal ask,
    BigDecimal impliedVolatility,
    BigDecimal delta,
    Long volume) {}
