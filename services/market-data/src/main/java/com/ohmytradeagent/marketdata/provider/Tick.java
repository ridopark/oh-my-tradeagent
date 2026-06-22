package com.ohmytradeagent.marketdata.provider;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A single streamed premium observation. {@code premium} is the value compared against the
 * chandelier threshold in {@code PositionWorkflow}; for an option this is the (provider-defined)
 * mid or last trade.
 *
 * <p>{@code bid}/{@code ask} carry the live NBBO from the same quote {@code premium} (the mid) was
 * computed from, when the source record is a quote. They are null for trade-record ticks and the
 * in-memory provider — consumers fall back to {@code premium}.
 */
public record Tick(
    String occSymbol,
    BigDecimal premium,
    BigDecimal bid,
    BigDecimal ask,
    OffsetDateTime retrievedAt) {

  public Tick(String occSymbol, BigDecimal premium, OffsetDateTime retrievedAt) {
    this(occSymbol, premium, null, null, retrievedAt);
  }
}
