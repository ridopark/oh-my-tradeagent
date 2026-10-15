package com.ohmytradeagent.tdbff.watchlist;

import com.ohmytradeagent.tdbff.proximity.MarketDataQuoteClient.EquityBar;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * "Did the stock cross the idea's level today, and when." Display-only and deliberately simpler
 * than the watchlist strategy's breakout rule: the FIRST regular-session minute whose high (ABOVE)
 * or low (BELOW) reaches the level counts, even when the stock gapped straight past it. The
 * operator sees where the stock is now and judges the gap; the strategy's tolerance band is a
 * trading decision this display does not make.
 */
public final class WatchlistCrossing {

  private WatchlistCrossing() {}

  /** Start of the first bar that reached {@code level}, or null when none did. */
  public static Instant firstCross(List<EquityBar> bars, String direction, BigDecimal level) {
    boolean above = "ABOVE".equals(direction);
    for (EquityBar b : bars) {
      boolean reached = above ? b.high().compareTo(level) >= 0 : b.low().compareTo(level) <= 0;
      if (reached) {
        return b.t();
      }
    }
    return null;
  }
}
