package com.ohmytradeagent.marketdata.provider;

/**
 * Handle for one active subscription on a {@link MarketDataProvider}. {@link #close()} detaches the
 * underlying listener without affecting other subscribers on the same symbol; the implementation
 * may also tear down the upstream feed when the last subscriber leaves (best-effort cleanup).
 *
 * <p>{@link #subscriptionId()} is opaque, unique per subscription, and propagated up to the {@code
 * SubscribePremiumResult} so operators can correlate audit lines with provider-side state.
 */
public interface Subscription extends AutoCloseable {

  String subscriptionId();

  @Override
  void close();
}
