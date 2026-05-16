package com.ohmytradeagent.orchestrator.domain;

public enum RejectionReason {
  AUTHOR_NOT_WHITELISTED,
  SIGNAL_TOO_OLD,
  INVALID_TIMESTAMP,
  /**
   * Issue #3 BTO secondary price-move gate. Reject BTO when the live bid/ask (mid) has moved more
   * than {@code StrategyConfig.bto_price_move_reject_pct} from {@code payload.price} since {@code
   * posted_at}, regardless of signal age. Documented spec for the gate; actual quote-fetch wiring
   * lands with market-data integration. The reason exists in the enum so future code can emit it
   * without forcing a contract bump.
   */
  BTO_PRICE_MOVED,
  KILL_SWITCH_TRIPPED,
  KILL_SWITCH_COOLING_DOWN,
  KILL_SWITCH_UNAVAILABLE,
  MAX_POSITIONS_EXCEEDED
}
