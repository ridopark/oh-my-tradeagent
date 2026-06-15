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

  /**
   * A stable identity of the CURRENT credentials for {@code (tenantId, provider)}. The registry
   * caches the built client keyed by this value and rebuilds (re-running the fail-closed
   * mode-coherence + account-identity assertion) when it changes — so an operator credential
   * rotation takes effect on the next resolution instead of after a pod restart.
   *
   * <p>The default is a compile-time CONSTANT: a source whose credentials never change at runtime
   * (the env-fallback) inherits it and is therefore NEVER rebuilt — by construction, not by
   * configuration. That is the proof the live env-default order path is byte-identical to
   * pre-P4-b-2 (the only delta is one constant-string compare per resolution). A source backed by
   * mutable storage (file mounts) overrides this with a cheap change-token (e.g. the mount's
   * last-modified time).
   */
  default String fingerprint(String tenantId, String provider) {
    return "static";
  }
}
