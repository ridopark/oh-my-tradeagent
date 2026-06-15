package com.ohmytradeagent.exec.broker.alpaca;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.exec.broker.BrokerClientRegistry;
import com.ohmytradeagent.exec.broker.BrokerCredentialSource;
import com.ohmytradeagent.exec.broker.BrokerCredentials;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.failure.ApplicationFailure;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Alpaca {@link BrokerClientRegistry}: maps each {@code (tenantId, provider)} key to an {@link
 * AlpacaPaperBroker} built from the key's resolved credentials, caching one client per account.
 *
 * <p>Activated for any {@code alpaca-*} broker.impl (mirrors {@link AlpacaConfig}); the {@code
 * BROKER_IMPL=stub} path uses {@code StubBrokerClientRegistry} instead, so exactly one {@link
 * BrokerClientRegistry} bean exists per profile.
 *
 * <p>Fail-closed build: {@link #build} runs the mode-coherence check and the P2 account-identity
 * assertion BEFORE the entry is published inside the per-key cache {@code compute} — a throw evicts
 * (never pre-put then validate), so a misconfigured key places no order and a later call
 * re-attempts the build.
 *
 * <p>Credential rotation: each cache entry is tagged with the source's credential {@code
 * fingerprint}; a resolution whose current fingerprint differs rebuilds the client (re-running the
 * fail-closed assertions), so an operator key rotation self-heals without a pod restart. The env
 * source's constant fingerprint never triggers a rebuild — the live path is unchanged.
 */
@Component
@ConditionalOnExpression("'${broker.impl:}'.startsWith('alpaca-')")
public class AlpacaBrokerClientRegistry implements BrokerClientRegistry {

  /** Provider this registry serves. A non-{@code alpaca} key is a routing bug → non-retryable. */
  private static final String PROVIDER = "alpaca";

  private final BrokerCredentialSource source;
  private final RestClient.Builder restClientBuilder;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;
  private final String brokerImpl;

  private final ConcurrentHashMap<BrokerKey, CachedBroker> cache = new ConcurrentHashMap<>();

  public AlpacaBrokerClientRegistry(
      BrokerCredentialSource source,
      RestClient.Builder restClientBuilder,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry,
      @Value("${broker.impl:}") String brokerImpl) {
    this.source = source;
    this.restClientBuilder = restClientBuilder;
    this.objectMapper = objectMapper;
    this.meterRegistry = meterRegistry;
    this.brokerImpl = brokerImpl;
  }

  @Override
  public OptionsBroker brokerFor(String tenantId, String provider) {
    if (!PROVIDER.equals(provider)) {
      // A non-alpaca provider reaching the alpaca worker is a routing/config bug, not a transient
      // condition — fail non-retryably (mirrors the exec InvalidBrokerTargetError precedent).
      throw ApplicationFailure.newNonRetryableFailure(
          "AlpacaBrokerClientRegistry cannot serve provider='"
              + provider
              + "' (tenant="
              + tenantId
              + "); this worker only serves provider='alpaca'",
          "InvalidBrokerTargetError");
    }
    BrokerKey key = new BrokerKey(tenantId, provider);
    // Credential-rotation invalidation: the source's fingerprint changes when the mounted creds
    // change (constant for the env source → never rebuilds → live path byte-identical). Computed
    // once outside compute so the per-key locked region is just a compare + (rarely) a build.
    String current = source.fingerprint(tenantId, provider);
    // compute() serializes the compare-or-rebuild per key (no torn (fingerprint, broker) value, no
    // concurrent double-build). NOTE: unlike computeIfAbsent, compute KEEPS the previous mapping if
    // the remapping throws — so a failed rebuild must EXPLICITLY evict (return null) rather than
    // throw, else a stale client would survive a rotation. We stash the failure and rethrow after,
    // preserving the fail-closed contract (no entry cached, next call re-attempts).
    RuntimeException[] failure = {null};
    CachedBroker result =
        cache.compute(
            key,
            (k, existing) -> {
              if (existing != null && existing.fingerprint().equals(current)) {
                return existing; // unchanged → reuse, no rebuild, no broker I/O.
              }
              try {
                // build() runs mode-coherence + the P2 account-identity assertion and only returns
                // a
                // VERIFIED broker; a throw here means the rotated creds failed (wrong account / bad
                // /
                // unreachable) → evict so no stale or unverified client is ever published.
                return new CachedBroker(current, build(k));
              } catch (RuntimeException e) {
                failure[0] = e;
                return null;
              }
            });
    if (result == null) {
      throw failure[0];
    }
    return result.broker();
  }

  /**
   * Builds + verifies the broker for {@code key}. Runs INSIDE the per-key cache {@code compute} so
   * the mode-coherence + account-identity assertions gate publication: a throw here evicts (no
   * entry cached) and {@code brokerFor} rethrows — fail-closed, no order against an unverified
   * account.
   */
  private OptionsBroker build(BrokerKey key) {
    BrokerCredentials creds = source.resolve(key.tenantId(), key.provider());

    // Cred presence + mode coherence (extracted verbatim from the pre-P4-a AlpacaConfig). The pod
    // is
    // single-mode in P4-a, so the brokerImpl suffix is the authoritative mode for the build.
    AlpacaModeCoherence.assertCredentialsPresent(creds.apiKeyId(), creds.apiSecretKey());
    AlpacaModeCoherence.assertCoherent(brokerImpl, creds.baseUrl(), creds.wsUrl());

    RestClient restClient =
        AlpacaConfig.buildRestClient(
            restClientBuilder, creds.baseUrl(), creds.apiKeyId(), creds.apiSecretKey());
    OptionsBroker broker = new AlpacaPaperBroker(restClient, objectMapper, meterRegistry);

    // P2 account-identity assertion (bounded transient-read retry, mismatch never retried). A throw
    // propagates out of compute (caught → evicted) → NO entry cached → fail-closed. A blank
    // expectedAccountId is a no-op (paper / back-compat). The only checked throw is
    // InterruptedException (retry sleep).
    try {
      BrokerAccountIdentityVerifier.verify(
          broker, creds.expectedAccountId(), "registry key " + key);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "interrupted verifying broker account identity for " + key, e);
    }

    return broker;
  }

  /** Cache key. tenantId is part of the key so P4-b's per-tenant creds get distinct clients. */
  record BrokerKey(String tenantId, String provider) {}

  /**
   * Cache value: the built+verified broker tagged with the credential {@code fingerprint} it was
   * built from. A later resolution rebuilds when the source's current fingerprint no longer matches
   * (an operator credential rotation), so the client self-heals without a pod restart.
   */
  record CachedBroker(String fingerprint, OptionsBroker broker) {}
}
