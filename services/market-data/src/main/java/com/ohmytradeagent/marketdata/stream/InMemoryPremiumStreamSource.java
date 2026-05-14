package com.ohmytradeagent.marketdata.stream;

import com.ohmytradeagent.contract.PremiumTick;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * Phase 4 in-memory fan-out implementation. The only {@link PremiumStreamSource} bean in Phase 4;
 * tick fan-out is driven by tests via {@link #pushTickForTest(String, BigDecimal, OffsetDateTime)}.
 *
 * <p>Concurrency: subscribers list per symbol is {@link CopyOnWriteArrayList} so concurrent
 * subscribe + push is safe. The {@code active} map uses {@link ConcurrentHashMap}.
 */
@Component
public class InMemoryPremiumStreamSource implements PremiumStreamSource {

  private record Listener(String subscriptionId, Consumer<PremiumTick> onTick) {}

  private final ConcurrentHashMap<String, Subscription> active = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, List<Listener>> bySymbol = new ConcurrentHashMap<>();

  @Override
  public Subscription subscribe(
      String optionSymbol, String positionWorkflowId, Consumer<PremiumTick> onTick) {
    String subscriptionId = UUID.randomUUID().toString();
    Subscription sub = new Subscription(subscriptionId, optionSymbol, positionWorkflowId);
    active.put(subscriptionId, sub);
    bySymbol
        .computeIfAbsent(optionSymbol, k -> new CopyOnWriteArrayList<>())
        .add(new Listener(subscriptionId, onTick));
    return sub;
  }

  @Override
  public void unsubscribe(String subscriptionId) {
    Subscription removed = active.remove(subscriptionId);
    if (removed == null) {
      return;
    }
    List<Listener> listeners = bySymbol.get(removed.optionSymbol());
    if (listeners != null) {
      listeners.removeIf(l -> l.subscriptionId().equals(subscriptionId));
    }
  }

  /**
   * Test-only fan-out hook. Phase 4 has no real wire source; tests drive this directly. A real
   * WS-backed source (Phase 7) will call its own internal dispatch instead.
   *
   * <p>Visible across packages because the activity test lives in a sibling package; production
   * code does not call this method.
   */
  public void pushTickForTest(String optionSymbol, BigDecimal premium, OffsetDateTime retrievedAt) {
    List<Listener> listeners = bySymbol.get(optionSymbol);
    if (listeners == null || listeners.isEmpty()) {
      return;
    }
    PremiumTick tick = new PremiumTick();
    tick.setSchemaVersion(1L);
    tick.setContractSymbol(optionSymbol);
    tick.setPremium(premium);
    tick.setRetrievedAt(retrievedAt);
    for (Listener l : listeners) {
      l.onTick().accept(tick);
    }
  }
}
