package com.ohmytradeagent.marketdata.stream;

/**
 * Handle returned by {@link PremiumStreamSource#subscribe}. Carries the subscription id (needed to
 * unsubscribe) and the original (symbol, positionWorkflowId) the subscription was created for.
 */
public record Subscription(String subscriptionId, String optionSymbol, String positionWorkflowId) {}
