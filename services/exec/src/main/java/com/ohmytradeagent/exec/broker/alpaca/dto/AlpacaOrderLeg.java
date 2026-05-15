package com.ohmytradeagent.exec.broker.alpaca.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One leg of an Alpaca multi-leg options order. For single-leg BTO/STC, the parent order's {@code
 * legs} array carries exactly one entry. Field names follow Alpaca's wire shape (snake_case via
 * {@link JsonProperty}) so callers can author records in idiomatic Java while serializing to
 * Alpaca's JSON contract.
 *
 * <p>{@code ratio_qty} is Alpaca's per-leg quantity multiplier; for single-leg orders it is always
 * {@code 1}. The actual contract count lives on the parent {@link AlpacaOrderRequest#qty()}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AlpacaOrderLeg(
    @JsonProperty("symbol") String symbol,
    @JsonProperty("ratio_qty") Long ratioQty,
    @JsonProperty("side") String side,
    @JsonProperty("position_intent") String positionIntent) {}
