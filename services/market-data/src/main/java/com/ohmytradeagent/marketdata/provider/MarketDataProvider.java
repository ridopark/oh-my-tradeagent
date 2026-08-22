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
   * One-shot REST snapshot of {@code occSymbol}'s implied volatility + greeks (#783), or {@link
   * Optional#empty()} when the provider has none. Display/recording-only — never drives a trigger.
   * Default empty so providers without a greeks surface (in-memory test fan-out) need no change.
   */
  default Optional<OptionGreeks> snapshotGreeks(String occSymbol) {
    return Optional.empty();
  }

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

  /**
   * Per-contract liveness of the option-premium poll, keyed by the SPACE-PADDED OCC. Display-only:
   * the tenant-dashboard BFF reads it so /live can distinguish an armed trail that is being fed
   * from one armed over a subscription nobody services (#717).
   *
   * <p>Defaulted to empty rather than abstract on purpose. A provider that does not poll (the
   * in-memory test/dev fan-out) has nothing truthful to report, and an empty map is read downstream
   * as "liveness unknown" — which renders the badge in its existing, pre-#717 form. Forcing a
   * fabricated implementation would make the dev provider assert a liveness it cannot observe.
   */
  default java.util.Map<String, PremiumFeedStatus> premiumFeedStatus() {
    return java.util.Map.of();
  }
}
