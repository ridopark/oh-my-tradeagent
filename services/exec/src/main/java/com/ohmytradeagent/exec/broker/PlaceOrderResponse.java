package com.ohmytradeagent.exec.broker;

/**
 * Outcome of a broker placeOrder attempt.
 *
 * <ul>
 *   <li>{@link #placed(String)} — the broker accepted a fresh order; {@code brokerOrderId} is set,
 *       {@code alreadyExisted=false}, {@code alreadyClosed=false}.
 *   <li>{@link #alreadyExisted(String)} — an idempotent re-place: the broker already holds this
 *       order (duplicate {@code client_order_id} / {@code existing_order_id}); {@code
 *       brokerOrderId} resolves to the prior order, {@code alreadyExisted=true}.
 *   <li>{@link #alreadyClosed()} — over-exit-422 benign outcome: the broker rejected a SELL/STC
 *       with a "position intent mismatch" 422 AND {@code /v2/positions} CONFIRMED the OCC is flat,
 *       so there was nothing to close. No order exists ({@code brokerOrderId=null}); the activity
 *       terminalizes the journal without paging.
 * </ul>
 */
public record PlaceOrderResponse(
    String brokerOrderId, boolean alreadyExisted, boolean alreadyClosed) {

  public static PlaceOrderResponse placed(String brokerOrderId) {
    return new PlaceOrderResponse(brokerOrderId, false, false);
  }

  public static PlaceOrderResponse alreadyExisted(String brokerOrderId) {
    return new PlaceOrderResponse(brokerOrderId, true, false);
  }

  // NOTE: named closedAlreadyFlat() (not alreadyClosed()) to avoid colliding with the
  // auto-generated record accessor alreadyClosed().
  public static PlaceOrderResponse closedAlreadyFlat() {
    return new PlaceOrderResponse(null, false, true);
  }
}
