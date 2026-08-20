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
  MAX_POSITIONS_EXCEEDED,
  /**
   * Issue #6 portfolio-level gate. Reject when (sum of open-position notional + this signal's
   * notional) exceeds {@code StrategyConfig.notional_cap_pct_of_capital_base * (cash +
   * sum_open_notional)}. Complements {@link #MAX_POSITIONS_EXCEEDED}, which only bounds the count
   * of concurrent positions. Issue #336: {@code notional_cap_pct_of_capital_base} is a deprecated
   * alias for {@code notional_cap_pct_of_capital_base}; this reason is also emitted with detail
   * {@code ambiguous_cap_config} when both fields are set to different values (fail-closed).
   */
  NOTIONAL_CAP_EXCEEDED,
  /**
   * Issue #6 portfolio-level gate. Reject when open positions on {@code payload.ticker} are already
   * at or above {@code StrategyConfig.same_underlying_count}.
   */
  SAME_UNDERLYING_LIMIT,
  /**
   * Issue #6 portfolio-level gate. Reject when open positions in the sector that {@code
   * payload.ticker} maps to (via {@code StrategyConfig.sector_overrides}) are already at or above
   * {@code StrategyConfig.sector_concentration_cap}. Unmapped tickers resolve to sector "unknown"
   * and are exempt.
   */
  SECTOR_CONCENTRATION_EXCEEDED,
  /**
   * Issue #6 portfolio-level gate. Reject when today's accepted BTO count is already at or above
   * {@code StrategyConfig.daily_trade_count}. Anti-overtrading circuit-breaker for volatile
   * sessions.
   */
  DAILY_TRADE_COUNT_EXCEEDED,
  /**
   * Issue #6 portfolio-level gate. Reject when the trailing-minute MTM loss rate exceeds {@code
   * StrategyConfig.drawdown_velocity_threshold} (dollars per minute). Intraday rate-of-loss circuit
   * breaker, complementing the cumulative {@code daily_loss_threshold}.
   */
  DRAWDOWN_VELOCITY_EXCEEDED,
  /**
   * Issue #6 portfolio-level gate. Reject when the exec-svc {@code pre_trade_check} Activity
   * returns {@code allowed=false}, {@code buying_power < estimated_notional}, {@code
   * pdt_status='BLOCKED'}, or {@code margin_sufficient=false}. Fails closed on any exception
   * (treats broker outage as a reject), mirroring {@link #KILL_SWITCH_UNAVAILABLE}.
   */
  PRE_TRADE_CHECK_FAILED
}
