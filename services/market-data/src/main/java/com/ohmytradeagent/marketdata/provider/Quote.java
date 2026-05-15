package com.ohmytradeagent.marketdata.provider;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Snapshot of an option contract's NBBO at a single point in time. {@code mid} is computed by the
 * provider (typically {@code (bid + ask) / 2}); the field is materialised so consumers never have
 * to re-derive it.
 */
public record Quote(
    String occSymbol, BigDecimal bid, BigDecimal mid, BigDecimal ask, OffsetDateTime retrievedAt) {}
