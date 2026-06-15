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
 * assertion BEFORE the entry is published inside {@code computeIfAbsent} — a throw leaves NO cached
 * entry (never pre-put then validate), so a misconfigured key places no order and a later call
 * re-attempts the build.
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

  private final ConcurrentHashMap<BrokerKey, OptionsBroker> cache = new ConcurrentHashMap<>();

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
    return cache.computeIfAbsent(new BrokerKey(tenantId, provider), this::build);
  }

  /**
   * Builds + verifies the broker for {@code key}. Runs INSIDE {@code computeIfAbsent} so the
   * mode-coherence + account-identity assertions gate publication: a throw here means no entry is
   * cached and {@code brokerFor} rethrows — fail-closed, no order against an unverified account.
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
    // propagates out of computeIfAbsent → NO entry cached → fail-closed. A blank expectedAccountId
    // is
    // a no-op (paper / back-compat). The only checked throw is InterruptedException (retry sleep).
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
}
