package com.ohmytradeagent.exec.broker;

/**
 * Single resolution authority for broker clients: maps a {@code (tenantId, provider)} key to the
 * {@link OptionsBroker} that serves it. Every broker consumer (place/cancel/getFillDetail
 * Activities, account/pre-trade/recon Activities, the fill poller) resolves its handle through here
 * instead of injecting a single shared {@link OptionsBroker} bean.
 *
 * <p>Implementations cache the built client per key (an atomic per-key build) so a client is built
 * once per account, never per call, and rebuilt only when the resolved credentials change. Building
 * runs the P2 account-identity assertion + mode-coherence check fail-closed BEFORE the entry is
 * published — a build failure leaves no cached entry and throws, so no order is placed against an
 * unverified account.
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
   * Returns the broker for {@code (tenantId, provider)}, building + caching it on first use, with
   * NO config-declared-account cross-check. Used by the account-level reads (snapshot / pre-trade /
   * reconciliation), the fill poller, and the boot warm-up — none of which carry a config-declared
   * account. Delegates to the 3-arg form with a {@code null} declared account.
   */
  default OptionsBroker brokerFor(String tenantId, String provider) {
    return brokerFor(tenantId, provider, null);
  }

  /**
   * Returns the broker for {@code (tenantId, provider)}, cross-checking that {@code
   * declaredAccountId} — the account the dispatching config declares (an OrderIntent's {@code
   * broker_account_id}) — matches the account the resolved credentials authenticate. The order path
   * passes the intent's declared account so a typo'd/inconsistent operator setup fails closed
   * (P4-c-b-2) instead of routing the order to the wrong account. A blank {@code declaredAccountId}
   * (today's tenants) or a blank authenticated account (paper / env back-compat) disables the
   * cross-check — mirroring the P2 blank-expected semantics — so the live path is byte-identical.
   * Throws non-retryably for an unknown provider or an account mismatch rather than returning an
   * unverified client.
   */
  OptionsBroker brokerFor(String tenantId, String provider, String declaredAccountId);

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
