package com.ohmytradeagent.exec.broker.alpaca.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * Alpaca position response shape, trimmed to the fields {@code AlpacaPaperBroker.listOpenPositions}
 * consumes. Alpaca returns dozens of additional fields (market value, cost basis, change today,
 * etc.); {@link JsonIgnoreProperties} suppresses unknowns.
 *
 * <p>Issue #165 Phase 3. We deliberately keep {@code qty} as {@link String} because Alpaca's
 * positions endpoint can return signed values as strings (e.g. {@code "5"}, {@code "-5"}) and the
 * adapter parses defensively. {@code assetClass} is the discriminator we filter on ({@code
 * "us_option"} — equities and crypto are excluded). {@code side} is {@code "long"} or {@code
 * "short"}; v0 of {@code BrokerPosition.side} only models {@code LONG}, so short positions are
 * dropped with a warn log.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlpacaPositionResponse(
    @JsonProperty("symbol") String symbol,
    @JsonProperty("asset_class") String assetClass,
    @JsonProperty("qty") String qty,
    @JsonProperty("side") String side,
    @JsonProperty("avg_entry_price") BigDecimal avgEntryPrice) {}
