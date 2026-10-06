package com.ohmytradeagent.exec.broker;

import java.math.BigDecimal;
import java.util.List;

/**
 * Gated-condor Phase 3: one OPENING multi-leg (2-4 legs) net-credit limit order, tif=day. {@code
 * netCredit} is the credit to RECEIVE per combo unit, always positive — adapters translate to the
 * venue's notation (Alpaca expresses an mleg credit as a NEGATIVE {@code limit_price}). Leg {@code
 * side} is the contract's uppercase {@code BUY}/{@code SELL}; every leg opens ({@code
 * buy_to_open}/{@code sell_to_open}).
 */
public record PlaceMlegOrderRequest(
    String tenantId, String clientOrderId, long qty, BigDecimal netCredit, List<Leg> legs) {

  public PlaceMlegOrderRequest {
    if (legs == null || legs.size() < 2 || legs.size() > 4) {
      throw new IllegalArgumentException("mleg order needs 2-4 legs, got " + legs);
    }
    if (netCredit == null || netCredit.signum() <= 0) {
      throw new IllegalArgumentException("netCredit must be positive, got " + netCredit);
    }
    legs = List.copyOf(legs);
  }

  public record Leg(String optionSymbol, String side, long ratioQty) {}
}
