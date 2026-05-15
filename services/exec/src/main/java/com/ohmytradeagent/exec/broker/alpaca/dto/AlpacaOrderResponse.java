package com.ohmytradeagent.exec.broker.alpaca.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Alpaca order response shape, trimmed to the fields the adapter consumes. Alpaca returns dozens of
 * additional fields (timestamps, asset metadata, fill detail); {@link JsonIgnoreProperties}
 * suppresses unknowns so a future Alpaca-side schema bump doesn't break us.
 *
 * <p>{@code status} values we map (Alpaca docs):
 *
 * <ul>
 *   <li>{@code new} / {@code accepted} / {@code pending_new} / {@code accepted_for_bidding} → OPEN
 *   <li>{@code partially_filled} → OPEN (parent stays open until terminal fill)
 *   <li>{@code filled} → FILLED
 *   <li>{@code canceled} / {@code expired} / {@code replaced} → CANCELLED
 *   <li>{@code rejected} → REJECTED
 *   <li>anything else (including {@code suspended}) → UNKNOWN
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlpacaOrderResponse(
    @JsonProperty("id") String id,
    @JsonProperty("client_order_id") String clientOrderId,
    @JsonProperty("status") String status) {}
