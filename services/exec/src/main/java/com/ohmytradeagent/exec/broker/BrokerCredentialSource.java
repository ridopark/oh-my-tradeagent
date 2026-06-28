package com.ohmytradeagent.exec.broker;

import io.temporal.failure.ApplicationFailure;
import java.util.Set;

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
   * The Temporal failure type every source raises when it cannot produce credentials. It is part of
   * the cross-service fail-closed contract (the registry/workflow keys redrive vs. abort off it,
   * and tests assert it), so it lives once on the seam rather than as a literal duplicated per
   * source.
   */
  String UNAVAILABLE_TYPE = "BrokerCredentialsUnavailable";

  /**
   * Builds the canonical non-retryable {@link #UNAVAILABLE_TYPE} failure. A
   * missing/blank/undecryptable credential is a deployment or config error, never transient — fail
   * closed, do not redrive. The message must never contain secret bytes (callers pass only
   * identifiers/paths).
   */
  static ApplicationFailure unavailable(String message) {
    return ApplicationFailure.newNonRetryableFailure(message, UNAVAILABLE_TYPE);
  }

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

  /**
   * The roster of tenants this source can serve credentials for, for the given provider. Phase G's
   * per-tenant fill listener enumerates this to open one authenticated trade-updates WebSocket per
   * live tenant (replacing the single pod-wide socket).
   *
   * <p>This roster is per-pod-broker-target-scoped BY CONSTRUCTION: each exec pod is deployed with
   * exactly one mode's credentials (the {@code env} cred set, OR the {@code file} mount tree, OR
   * the {@code db} rows for that pod's broker), so no extra {@code broker_target} filter is needed
   * — whatever this source can resolve is exactly that pod's account(s).
   *
   * <p>The default is the EMPTY set: a source that cannot cheaply enumerate its tenants (or has no
   * meaningful roster) contributes none, and the per-tenant listener simply opens no sockets for
   * it. The three concrete sources override this:
   *
   * <ul>
   *   <li>env-fallback → the single bootstrap tenant id (its only account)
   *   <li>file-mounted → the per-tenant {@code <tenant>-<provider>} directories under the mount
   *       root
   *   <li>db → {@code SELECT DISTINCT tenant_id WHERE provider = ?}
   * </ul>
   */
  default Set<String> liveTenants(String provider) {
    return Set.of();
  }
}
