package com.ohmytradeagent.exec.broker;

/**
 * Single resolution authority for broker clients: maps a {@code (tenantId, provider)} key to the
 * {@link OptionsBroker} that serves it. Every broker consumer (place/cancel/getFillDetail
 * Activities, account/pre-trade/recon Activities, the fill poller) resolves its handle through here
 * instead of injecting a single shared {@link OptionsBroker} bean.
 *
 * <p>Implementations cache the built client per key ({@code computeIfAbsent}) so a client is built
 * once per account, never per call. Building runs the P2 account-identity assertion +
 * mode-coherence check fail-closed BEFORE the entry is published — a build failure leaves no cached
 * entry and throws, so no order is placed against an unverified account.
 */
public interface BrokerClientRegistry {

  /**
   * Sentinel tenant key for account-level resolutions (account snapshot, pre-trade, reconciliation)
   * that carry no tenant — there is one credential set per exec deployment, so a stable sentinel
   * keeps a single cached client. A registry-key convention, owned here rather than by any one
   * activity.
   */
  String ACCOUNT_LEVEL = "__account_level__";

  /**
   * Returns the broker for {@code (tenantId, provider)}, building + caching it on first use. Throws
   * (non-retryably for an unknown provider, fail-closed for a mode/account mismatch) rather than
   * returning an unverified client.
   */
  OptionsBroker brokerFor(String tenantId, String provider);

  /**
   * Extracts the provider from a {@code broker_target} value: the substring before the first {@code
   * '-'} (e.g. {@code "alpaca-paper"} → {@code "alpaca"}). A value with no {@code '-'} (the legacy
   * bare {@code "paper"} / {@code "live"}) is returned unchanged; such a value never routes to a
   * real worker (no broker polls {@code broker-paper}), so it surfaces downstream as an unknown
   * provider.
   */
  static String providerOf(String brokerTarget) {
    if (brokerTarget == null) {
      return null;
    }
    int dash = brokerTarget.indexOf('-');
    return dash < 0 ? brokerTarget : brokerTarget.substring(0, dash);
  }
}
