package com.ohmytradeagent.exec.broker.alpaca.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

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
 *
 * <p>Issue #165: {@code filled_qty}, {@code filled_avg_price}, {@code filled_at} are read by {@code
 * getFillDetail} when reconciling a cancel-on-filled race. Alpaca returns the price/qty as JSON
 * strings; Jackson deserialises both to {@link BigDecimal} / {@link Long} cleanly via the default
 * config (jackson-datatype-jsr310 handles {@link OffsetDateTime}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlpacaOrderResponse(
    @JsonProperty("id") String id,
    @JsonProperty("client_order_id") String clientOrderId,
    @JsonProperty("status") String status,
    @JsonProperty("filled_qty") Long filledQty,
    @JsonProperty("filled_avg_price") BigDecimal filledAvgPrice,
    @JsonProperty("filled_at") OffsetDateTime filledAt) {}
