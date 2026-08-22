package com.ohmytradeagent.orchestrator.alert.tradecontext;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Issue #783: one entry-time snapshot for a newly observed position, written once per {@code
 * (signalId, tenantId)} into the dashboard DB's {@code trade_context} table. Every field except the
 * key/identity ones may be null — a missing quote records nulls plus {@code quoteState="unknown"},
 * never nothing (the fields are unrecoverable later, so a partial row beats no row).
 *
 * <p>Account equity is deliberately ABSENT: it lives behind a broker call this read-only component
 * must not make, so the {@code equity} column stays null (documented in the V13 migration).
 */
public record TradeContextEntry(
    String signalId,
    String tenantId,
    String strategyId,
    String workflowId,
    String contractSymbol,
    OffsetDateTime entryAt,
    BigDecimal entryPremium,
    long entryQty,
    BigDecimal entryBid,
    BigDecimal entryAsk,
    BigDecimal entrySpread,
    BigDecimal iv,
    BigDecimal delta,
    BigDecimal gamma,
    BigDecimal theta,
    BigDecimal vega,
    BigDecimal underlyingSpot,
    Integer dte,
    BigDecimal moneyness,
    BigDecimal capitalWeight,
    String quoteState) {}
