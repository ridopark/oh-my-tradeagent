package com.ohmytradeagent.exec.broker.alpaca.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * Alpaca {@code POST /v2/orders} request body for a single-leg options order. Wire shape per <a
 * href="https://docs.alpaca.markets/reference/postoptionorder">postoptionorder</a>.
 *
 * <p>Flat shape: {@code symbol} + {@code qty} + {@code side} + {@code position_intent} live at the
 * order level. The earlier {@code order_class=mleg} + {@code legs} variant returned a 422 from
 * Alpaca ("mleg orders must have at least 2 legs and at most 4 legs") — that order class is
 * reserved for genuine multi-leg strategies (vertical / iron condor / etc.) and the single-leg
 * shape uses no {@code order_class} at all.
 *
 * <p>{@code type} is derived from {@code limitPrice} nullness by the caller — {@code limit} when a
 * price is supplied, {@code market} otherwise — with {@code time_in_force=day} as the v0 default
 * for both.
 *
 * <p>{@code limit_price} is a {@link BigDecimal} so Jackson serializes it as a JSON number, which
 * Alpaca's live endpoint requires (the sandbox accepts strings but live does not).
 *
 * <p>{@code client_order_id} carries our {@code intent_key} verbatim; Alpaca dedups on it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AlpacaOrderRequest(
    @JsonProperty("symbol") String symbol,
    @JsonProperty("qty") Long qty,
    @JsonProperty("side") String side,
    @JsonProperty("type") String type,
    @JsonProperty("time_in_force") String timeInForce,
    @JsonProperty("position_intent") String positionIntent,
    @JsonProperty("limit_price") BigDecimal limitPrice,
    @JsonProperty("client_order_id") String clientOrderId) {}
