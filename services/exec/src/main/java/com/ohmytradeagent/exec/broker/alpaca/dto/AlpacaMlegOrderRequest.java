package com.ohmytradeagent.exec.broker.alpaca.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

/**
 * Alpaca {@code POST /v2/orders} request body for a multi-leg ({@code order_class=mleg}) options
 * order — 2 to 4 legs, {@code limit}/{@code market} only, {@code day}/{@code gtc} only. Wire shape
 * per <a href="https://docs.alpaca.markets/reference/postorder">postorder</a>.
 *
 * <p>{@code limit_price} is the NET price per combo unit in Alpaca's mleg notation: positive =
 * debit paid, NEGATIVE = credit received. Serialized as a JSON number (live rejects strings, see
 * {@link AlpacaOrderRequest}). {@code client_order_id} carries the bounded id Alpaca dedups on.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AlpacaMlegOrderRequest(
    @JsonProperty("order_class") String orderClass,
    @JsonProperty("qty") Long qty,
    @JsonProperty("type") String type,
    @JsonProperty("time_in_force") String timeInForce,
    @JsonProperty("limit_price") BigDecimal limitPrice,
    @JsonProperty("client_order_id") String clientOrderId,
    @JsonProperty("legs") List<Leg> legs) {

  public record Leg(
      @JsonProperty("symbol") String symbol,
      @JsonProperty("ratio_qty") Long ratioQty,
      @JsonProperty("side") String side,
      @JsonProperty("position_intent") String positionIntent) {}
}
