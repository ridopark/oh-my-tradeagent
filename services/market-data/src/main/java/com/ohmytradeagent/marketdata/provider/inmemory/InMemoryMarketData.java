package com.ohmytradeagent.marketdata.provider.inmemory;

import com.ohmytradeagent.marketdata.provider.MarketDataProvider;
import com.ohmytradeagent.marketdata.provider.Quote;
import com.ohmytradeagent.marketdata.provider.Subscription;
import com.ohmytradeagent.marketdata.provider.Tick;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default in-process {@link MarketDataProvider} for dev + tests. Selected when {@code
 * market-data.provider} is unset or set to {@code inmemory}. Tests drive {@link #pushTickForTest}
 * directly; production deploys flip {@code MARKET_DATA_PROVIDER=alpaca} to swap in the Alpaca
 * adapter.
 */
@Component
@ConditionalOnProperty(
    name = "market-data.provider",
    havingValue = "inmemory",
    matchIfMissing = true)
public class InMemoryMarketData implements MarketDataProvider {

  private record Listener(String subscriptionId, Consumer<Tick> onTick) {}

  private final ConcurrentHashMap<String, List<Listener>> bySymbol = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, List<Listener>> byTicker = new ConcurrentHashMap<>();

  @Override
  public Optional<Quote> snapshotQuote(String occSymbol) {
    // No backing feed in the in-memory provider — return empty so callers fail open instead of
    // attempting to act on synthetic NBBO. Tests that need a quote can extend this with a
    // pushQuoteForTest helper if/when they need that path.
    return Optional.empty();
  }

  @Override
  public Optional<BigDecimal> snapshotEquityPrice(String ticker) {
    // No backing feed in the in-memory provider — empty so the dashboard renders "-" rather than a
    // synthetic price.
    return Optional.empty();
  }

  @Override
  public Subscription subscribePremium(String occSymbol, Consumer<Tick> onTick) {
    String id = UUID.randomUUID().toString();
    bySymbol
        .computeIfAbsent(occSymbol, k -> new CopyOnWriteArrayList<>())
        .add(new Listener(id, onTick));
    return new InMemorySubscription(bySymbol, id, occSymbol);
  }

  @Override
  public Subscription subscribeEquity(String ticker, Consumer<Tick> onTick) {
    String id = UUID.randomUUID().toString();
    byTicker
        .computeIfAbsent(ticker, k -> new CopyOnWriteArrayList<>())
        .add(new Listener(id, onTick));
    return new InMemorySubscription(byTicker, id, ticker);
  }

  /**
   * Test-only equity fan-out hook. Mirrors {@link #pushTickForTest}; the {@code premium} field of
   * the delivered {@link Tick} carries the underlying's last trade price.
   */
  public void pushEquityTickForTest(String ticker, BigDecimal last, OffsetDateTime retrievedAt) {
    List<Listener> listeners = byTicker.get(ticker);
    if (listeners == null || listeners.isEmpty()) {
      return;
    }
    Tick tick = new Tick(ticker, last, retrievedAt);
    for (Listener l : listeners) {
      l.onTick().accept(tick);
    }
  }

  /**
   * Test-only fan-out hook.
   *
   * <p>Visible for testing only — production paths receive ticks from the real provider's wire
   * feed. The repo doesn't carry a {@code @VisibleForTesting} annotation (no Guava on the
   * classpath), so this Javadoc is the marker. Do not call from non-test code.
   */
  public void pushTickForTest(String occSymbol, BigDecimal premium, OffsetDateTime retrievedAt) {
    List<Listener> listeners = bySymbol.get(occSymbol);
    if (listeners == null || listeners.isEmpty()) {
      return;
    }
    Tick tick = new Tick(occSymbol, premium, retrievedAt);
    for (Listener l : listeners) {
      l.onTick().accept(tick);
    }
  }

  private static final class InMemorySubscription implements Subscription {
    private final ConcurrentHashMap<String, List<Listener>> registry;
    private final String id;
    private final String symbol;

    InMemorySubscription(
        ConcurrentHashMap<String, List<Listener>> registry, String id, String symbol) {
      this.registry = registry;
      this.id = id;
      this.symbol = symbol;
    }

    @Override
    public String subscriptionId() {
      return id;
    }

    @Override
    public void close() {
      List<Listener> listeners = registry.get(symbol);
      if (listeners == null) {
        return;
      }
      listeners.removeIf(l -> l.subscriptionId().equals(id));
      // Mirror the Alpaca path: evict empty lists so the map doesn't grow unboundedly across
      // subscribe/close churn. Use two-arg remove so a concurrent subscribe that inserted a
      // fresh CopyOnWriteArrayList under the same key isn't clobbered.
      if (listeners.isEmpty()) {
        registry.remove(symbol, listeners);
      }
    }
  }
}
