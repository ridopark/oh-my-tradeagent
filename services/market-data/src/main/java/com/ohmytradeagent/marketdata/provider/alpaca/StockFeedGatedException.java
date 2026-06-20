package com.ohmytradeagent.marketdata.provider.alpaca;

/**
 * Thrown by {@code AlpacaMarketData.subscribeEquity} when the real-time stock WS feed is
 * unconfigured (fail-closed live-use gate). Typed so callers distinguish the gated case from a
 * generic subscription failure without string-matching the message.
 */
public class StockFeedGatedException extends RuntimeException {
  public StockFeedGatedException(String message) {
    super(message);
  }
}
