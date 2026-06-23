package com.ohmytradeagent.marketdata.provider;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Phase 2c.2 provider-agnostic port for option market data. Implementations live under {@code
 * provider/<vendor>/} (e.g. {@link com.ohmytradeagent.marketdata.provider.alpaca}) and are selected
 * at boot via {@code market-data.provider=<vendor>}.
 *
 * <p>Two methods, no state on the port itself:
 *
 * <ul>
 *   <li>{@link #snapshotQuote(String)} — one-shot REST quote, used for sanity checks and seeding.
 *   <li>{@link #subscribePremium(String, Consumer)} — push subscription. The provider drives the
 *       given {@link Consumer} for each premium tick until the returned {@link Subscription} is
 *       {@link Subscription#close() closed}.
 * </ul>
 *
 * <p>Phase 2c.2 keeps the legacy {@code PremiumStreamSource} for in-memory test fan-out; production
 * code paths (the Alpaca adapter) implement this port directly.
 */
public interface MarketDataProvider {

  /**
   * Returns the current bid/mid/ask premium for {@code occSymbol}, or {@link Optional#empty()} when
   * the provider's snapshot is unavailable or stale.
   */
  Optional<Quote> snapshotQuote(String occSymbol);

  /**
   * One-shot REST last-trade price for an underlying equity {@code ticker} (e.g. {@code NVDA}), or
   * {@link Optional#empty()} when the snapshot is unavailable. Display-only (the dashboard /live
   * underlying spot); never drives a trigger, so it is NOT subject to the RTH/feed-entitlement gate
   * that {@link #subscribeEquity} enforces.
   */
  Optional<BigDecimal> snapshotEquityPrice(String ticker);

  /**
   * Opens a push subscription for {@code occSymbol}. Each premium tick from the provider feed is
   * delivered to {@code onTick}. Subscriptions are independent: closing one does not affect other
   * subscribers on the same symbol.
   */
  Subscription subscribePremium(String occSymbol, Consumer<Tick> onTick);

  /**
   * Opens a push subscription for an underlying equity {@code ticker} (e.g. {@code NVDA}). Each
   * stock trade print from the provider feed is delivered to {@code onTick} as a {@link Tick} whose
   * {@code premium} carries the last trade price. Halted/stale prints are dropped by the
   * implementation and never delivered. Subscriptions are independent: closing one does not affect
   * other subscribers on the same ticker.
   *
   * <p>Live use is gated: a provider whose stock-data feed is not explicitly configured MUST fail
   * closed (loud audit, no connect) rather than drive triggers off a wrong/delayed feed.
   */
  Subscription subscribeEquity(String ticker, Consumer<Tick> onTick);
}
