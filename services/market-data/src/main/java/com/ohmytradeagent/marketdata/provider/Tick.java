package com.ohmytradeagent.marketdata.provider;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A single streamed premium observation. {@code premium} is the value compared against the
 * chandelier threshold in {@code PositionWorkflow}; for an option this is the (provider-defined)
 * mid or last trade.
 */
public record Tick(String occSymbol, BigDecimal premium, OffsetDateTime retrievedAt) {}
