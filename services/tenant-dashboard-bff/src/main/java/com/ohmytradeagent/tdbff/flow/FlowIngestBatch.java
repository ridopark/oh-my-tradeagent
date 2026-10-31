package com.ohmytradeagent.tdbff.flow;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.ohmytradeagent.tdbff.flow.FlowRepository.Order;
import com.ohmytradeagent.tdbff.flow.FlowRepository.TickerDay;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The body of {@code POST /internal/flow/ingest}, as Jackson binds it. {@link #validate()} rejects
 * anything the store could not hold or dedupe; an {@link IllegalArgumentException} becomes the
 * service's standard 400 via {@code GlobalExceptionHandler}. Messages name the WIRE field
 * (snake_case, as the records' {@code @JsonNaming} binds it), never echo the caller's value.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record FlowIngestBatch(List<Order> orders, List<TickerDay> tickerDays) {

  /** Bounds one POST, and so one transaction. */
  public static final int MAX_ROWS = 5000;

  /** Mirrors the V16 CHECK constraint on {@code flow_order.side}. */
  public static final Set<String> SIDES = Set.of("buy", "sell", "mid", "unknown");

  /** Mirrors the V16 CHECK constraint on {@code flow_order.side_source}. */
  static final Set<String> SIDE_SOURCES = Set.of("quote", "none");

  /** Mirrors the V16 CHECK constraint on {@code flow_order.option_right} (wire name: right). */
  public static final Set<String> RIGHTS = Set.of("C", "P");

  private static final Pattern TICKER = Pattern.compile("[A-Z0-9.\\-]{1,16}");

  public FlowIngestBatch {
    orders = orders == null ? List.of() : orders;
    tickerDays = tickerDays == null ? List.of() : tickerDays;
  }

  public void validate() {
    if (orders.size() > MAX_ROWS) {
      throw new IllegalArgumentException("too many orders (max " + MAX_ROWS + ")");
    }
    if (tickerDays.size() > MAX_ROWS) {
      throw new IllegalArgumentException("too many ticker_days (max " + MAX_ROWS + ")");
    }
    for (Order o : orders) {
      validateOrder(o);
    }
    for (TickerDay t : tickerDays) {
      validateTickerDay(t);
    }
  }

  private static void validateOrder(Order o) {
    if (o == null) {
      throw new IllegalArgumentException("order is null");
    }
    // The unique key: a missing order_id cannot dedupe on replay.
    require(o.orderId() != null && !o.orderId().isBlank(), "order.order_id");
    requireTicker(o.ticker(), "order.ticker");
    require(o.contract() != null && !o.contract().isBlank(), "order.contract");
    require(o.expiry() != null, "order.expiry");
    require(o.strike() != null && o.strike().signum() > 0, "order.strike");
    require(fits(o.strike(), 12, 4), "order.strike");
    require(o.right() != null && RIGHTS.contains(o.right()), "order.right");
    require(o.firstTs() != null, "order.first_ts");
    require(o.lastTs() != null, "order.last_ts");
    require(o.tradeCount() != null && o.tradeCount() > 0, "order.trade_count");
    require(o.exchangeCount() != null && o.exchangeCount() > 0, "order.exchange_count");
    require(o.size() != null && o.size() > 0, "order.size");
    require(o.premium() != null && o.premium().signum() > 0, "order.premium");
    require(fits(o.premium(), 16, 2), "order.premium");
    require(o.vwap() != null && o.vwap().signum() > 0, "order.vwap");
    require(fits(o.vwap(), 12, 4), "order.vwap");
    require(o.side() != null && SIDES.contains(o.side()), "order.side");
    require(o.sideSource() != null && SIDE_SOURCES.contains(o.sideSource()), "order.side_source");
    require(o.bid() == null || o.bid().signum() >= 0, "order.bid");
    require(fits(o.bid(), 12, 4), "order.bid");
    require(o.ask() == null || o.ask().signum() >= 0, "order.ask");
    require(fits(o.ask(), 12, 4), "order.ask");
    require(o.sweep() != null, "order.sweep");
    require(o.multiLeg() != null, "order.multi_leg");
    require(o.auction() != null, "order.auction");
    require(o.cross() != null, "order.cross");
    require(o.block() != null, "order.block");
    require(o.dte() != null && o.dte() >= 0, "order.dte");
    require(fits(o.underlyingPrice(), 12, 4), "order.underlying_price");
    require(fits(o.moneynessPct(), 8, 4), "order.moneyness_pct");
    require(
        o.blockPremiumThreshold() != null && o.blockPremiumThreshold().signum() >= 0,
        "order.block_premium_threshold");
    require(fits(o.blockPremiumThreshold(), 16, 2), "order.block_premium_threshold");
    require(
        o.blockSizeThreshold() != null && o.blockSizeThreshold() >= 0,
        "order.block_size_threshold");
    require(o.orderWindowMs() != null && o.orderWindowMs() > 0, "order.order_window_ms");
    require(o.quoteMaxAgeMs() != null && o.quoteMaxAgeMs() > 0, "order.quote_max_age_ms");
  }

  private static void validateTickerDay(TickerDay t) {
    if (t == null) {
      throw new IllegalArgumentException("ticker_day is null");
    }
    requireTicker(t.ticker(), "ticker_day.ticker");
    require(t.sessionDate() != null, "ticker_day.session_date");
    require(t.callPremium() != null && t.callPremium().signum() >= 0, "ticker_day.call_premium");
    require(fits(t.callPremium(), 16, 2), "ticker_day.call_premium");
    require(t.putPremium() != null && t.putPremium().signum() >= 0, "ticker_day.put_premium");
    require(fits(t.putPremium(), 16, 2), "ticker_day.put_premium");
    require(
        t.boughtPremium() != null && t.boughtPremium().signum() >= 0, "ticker_day.bought_premium");
    require(fits(t.boughtPremium(), 16, 2), "ticker_day.bought_premium");
    require(t.soldPremium() != null && t.soldPremium().signum() >= 0, "ticker_day.sold_premium");
    require(fits(t.soldPremium(), 16, 2), "ticker_day.sold_premium");
    require(t.midPremium() != null && t.midPremium().signum() >= 0, "ticker_day.mid_premium");
    require(fits(t.midPremium(), 16, 2), "ticker_day.mid_premium");
    require(
        t.unknownPremium() != null && t.unknownPremium().signum() >= 0,
        "ticker_day.unknown_premium");
    require(fits(t.unknownPremium(), 16, 2), "ticker_day.unknown_premium");
    require(t.tradeCount() != null && t.tradeCount() >= 0, "ticker_day.trade_count");
    require(t.orderCount() != null && t.orderCount() >= 0, "ticker_day.order_count");
    require(
        t.quotedPremiumShare() == null || t.quotedPremiumShare().signum() >= 0,
        "ticker_day.quoted_premium_share");
    require(fits(t.quotedPremiumShare(), 8, 4), "ticker_day.quoted_premium_share");
  }

  private static void requireTicker(String ticker, String field) {
    require(ticker != null && TICKER.matcher(ticker).matches(), field);
  }

  private static void require(boolean ok, String field) {
    if (!ok) {
      throw new IllegalArgumentException("invalid or missing " + field);
    }
  }

  /**
   * Returns true when {@code value} fits in a NUMERIC(precision, scale) column: the integer portion
   * has at most {@code precision - scale} digits. Null always fits (stored as NULL).
   *
   * <p>Example: fits(new BigDecimal("9999.9999"), 8, 4) → true (4 int digits = max). fits(new
   * BigDecimal("10000"), 8, 4) → false (5 int digits > 4 max).
   */
  static boolean fits(BigDecimal value, int precision, int scale) {
    if (value == null) {
      return true;
    }
    int maxIntDigits = precision - scale;
    BigDecimal intPart = value.abs().setScale(0, java.math.RoundingMode.DOWN);
    if (intPart.signum() == 0) {
      return true;
    }
    return intPart.precision() <= maxIntDigits;
  }
}
