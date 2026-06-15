package com.ohmytradeagent.exec.broker;

/**
 * Resolves the {@link BrokerCredentials} for a {@code (tenantId, provider)} key. The seam that P4-b
 * swaps from the single env cred set to per-tenant scoped secrets.
 *
 * <p>P4-a ships exactly one implementation — {@code EnvFallbackBrokerCredentialSource} — which
 * IGNORES {@code tenantId} and returns the single env cred set for every tenant, so the resolved
 * broker is byte-identical to the pre-P4-a single broker.
 */
public interface BrokerCredentialSource {

  /**
   * Resolve credentials for the given tenant + provider (e.g. {@code "alpaca"}). Implementations
   * must be side-effect-free and safe to call on the broker hot path (the registry caches the built
   * client, but a cache miss calls this).
   */
  BrokerCredentials resolve(String tenantId, String provider);
}
