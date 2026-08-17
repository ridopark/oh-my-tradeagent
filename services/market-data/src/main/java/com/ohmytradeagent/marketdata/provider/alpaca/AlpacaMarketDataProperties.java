package com.ohmytradeagent.marketdata.provider.alpaca;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Boot-bound Alpaca market-data config. {@code apiKeyId} / {@code apiSecretKey} are required when
 * {@code market-data.provider=alpaca}; {@link AlpacaMarketDataConfig#alpacaMarketDataRestClient}
 * fails fast on missing values so a misconfigured paper deployment cannot silently boot and 401 on
 * every quote.
 *
 * @param dataBaseUrl base URL for REST snapshots (e.g. {@code https://data.alpaca.markets})
 * @param dataWsUrl NO LONGER USED for the options premium feed — that is now a REST poll of {@code
 *     snapshotQuote} ({@code AlpacaMarketData#pollOnce}). Retained to avoid config/test churn; the
 *     Alpaca options WS (v1beta1) is msgpack/binary + message-auth and was never delivering ticks.
 * @param premiumPollIntervalMs option-premium snapshot poll cadence in ms; null -> 500ms default
 *     ({@link #effectivePremiumPollIntervalMs()}). One poll per distinct open OCC = 60000/interval
 *     req/min/contract. The budget is 10,000 req/min (Algo Trader Plus) — NOT the "~200 req/min"
 *     this javadoc claimed until 2026-08-17, which is the Basic-plan limit this account has never
 *     been on. Keep this default in lockstep with {@code application.yml}: two disagreeing defaults
 *     for one knob is the drift this change was meant to avoid.
 * @param stockDataWsUrl WebSocket URL for the real-time STOCK trade stream (e.g. {@code
 *     wss://stream.data.alpaca.markets/v2/iex} or {@code .../v2/sip}). LIVE-USE GATE: intentionally
 *     UNSET by default. When blank, {@link AlpacaMarketData#subscribeEquity} fails closed (loud
 *     audit, no connect) — a delayed/wrong stock feed must never silently drive watchlist triggers.
 *     An operator opts in by setting {@code market-data.alpaca.stock-data-ws-url} (or {@code
 *     stock-feed}) once a real-time entitlement is confirmed.
 * @param stockFeed convenience selector ({@code iex} | {@code sip}); when {@code stockDataWsUrl} is
 *     blank but {@code stockFeed} is set, the effective URL is {@code
 *     wss://stream.data.alpaca.markets/v2/<stockFeed>}. Blank by default (keeps the gate closed).
 */
@ConfigurationProperties(prefix = "market-data.alpaca")
public record AlpacaMarketDataProperties(
    String dataBaseUrl,
    String dataWsUrl,
    String apiKeyId,
    String apiSecretKey,
    String stockDataWsUrl,
    String stockFeed,
    Long premiumPollIntervalMs) {

  /** Poll cadence (ms) for the option-premium snapshot; defaults to 500ms when unset. */
  public long effectivePremiumPollIntervalMs() {
    return (premiumPollIntervalMs != null && premiumPollIntervalMs > 0)
        ? premiumPollIntervalMs
        : 500L;
  }

  /**
   * Effective stock-stream WS URL, or empty when live equity use is not enabled. Prefers an
   * explicit {@code stockDataWsUrl}; else derives from {@code stockFeed}; else empty (fail-closed).
   */
  public java.util.Optional<String> effectiveStockDataWsUrl() {
    if (stockDataWsUrl != null && !stockDataWsUrl.isBlank()) {
      return java.util.Optional.of(stockDataWsUrl.trim());
    }
    if (stockFeed != null && !stockFeed.isBlank()) {
      String feed = stockFeed.trim().toLowerCase();
      if (!"iex".equals(feed) && !"sip".equals(feed)) {
        throw new IllegalStateException(
            "market-data.alpaca.stock-feed must be 'iex' or 'sip', got: " + stockFeed);
      }
      return java.util.Optional.of("wss://stream.data.alpaca.markets/v2/" + feed);
    }
    return java.util.Optional.empty();
  }
}
