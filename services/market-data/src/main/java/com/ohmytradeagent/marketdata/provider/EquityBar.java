package com.ohmytradeagent.marketdata.provider;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One 1-minute stock bar with its range, for the /live chain picker's "did the stock cross the
 * watchlist level, and when" — a level is crossed when the bar's high (or low) reaches it, which a
 * close-only {@link Bar} cannot show.
 */
public record EquityBar(Instant t, BigDecimal high, BigDecimal low, BigDecimal close) {}
