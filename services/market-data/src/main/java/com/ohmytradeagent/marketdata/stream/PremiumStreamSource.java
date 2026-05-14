package com.ohmytradeagent.marketdata.stream;

import com.ohmytradeagent.contract.PremiumTick;
import java.util.function.Consumer;

/**
 * Phase 4 port for a premium tick stream. The market-data worker uses this from the
 * SubscribePremiumActivity. Phase 4 ships only {@link InMemoryPremiumStreamSource}; a real broker
 * WS adapter (Tradier WS, etc.) lands in Phase 7.
 */
public interface PremiumStreamSource {

  /**
   * Subscribes a listener for ticks on {@code optionSymbol}. {@code positionWorkflowId} is recorded
   * on the returned {@link Subscription} so the activity layer can route fan-outs back into the
   * originating workflow.
   */
  Subscription subscribe(
      String optionSymbol, String positionWorkflowId, Consumer<PremiumTick> onTick);

  /** Stops fan-out for the given subscription id. No-op if unknown. */
  void unsubscribe(String subscriptionId);
}
