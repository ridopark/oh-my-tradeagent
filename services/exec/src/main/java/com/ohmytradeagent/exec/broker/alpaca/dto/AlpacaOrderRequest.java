package com.ohmytradeagent.exec.broker.alpaca.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Alpaca {@code POST /v2/orders} request body for a single-leg options order. Wire shape per <a
 * href="https://docs.alpaca.markets/reference/postoptionorder">postoptionorder</a>.
 *
 * <p>For a vanilla BTO/STC of one option contract we send {@code order_class=mleg} with a single
 * {@link AlpacaOrderLeg} entry; this is the documented shape that lets the adapter generalize to
 * multi-leg later without a request-DTO swap. {@code type=limit} + {@code time_in_force=day} is the
 * conservative pair for paper.
 *
 * <p>{@code client_order_id} carries our {@code intent_key} verbatim; Alpaca dedups on it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AlpacaOrderRequest(
    @JsonProperty("order_class") String orderClass,
    @JsonProperty("qty") Long qty,
    @JsonProperty("limit_price") String limitPrice,
    @JsonProperty("type") String type,
    @JsonProperty("time_in_force") String timeInForce,
    @JsonProperty("client_order_id") String clientOrderId,
    @JsonProperty("legs") List<AlpacaOrderLeg> legs) {}
