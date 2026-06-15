package com.ohmytradeagent.exec.broker.alpaca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.exec.broker.BrokerCredentialSource;
import com.ohmytradeagent.exec.broker.BrokerCredentials;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.temporal.failure.ApplicationFailure;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * P4-a: the per-key broker registry. Pins caching (one build per key), distinct clients per tenant,
 * env-fallback returns today's creds for any tenant, fail-closed on account mismatch + mode
 * incoherence (no cached entry; a 2nd call re-attempts), blank expected → builds OK, and unknown
 * provider → non-retryable.
 */
class AlpacaBrokerClientRegistryTest {

  private MockWebServer server;
  private String baseUrl;
  private final ObjectMapper mapper = new ObjectMapper();
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final RestClient.Builder builder = RestClient.builder();

  @BeforeEach
  void start() throws IOException {
    server = new MockWebServer();
    server.start();
    baseUrl = server.url("/").toString().replaceAll("/$", "");
  }

  @AfterEach
  void stop() throws IOException {
    server.shutdown();
  }

  /** A credential source that counts resolve() calls and returns a fixed cred set per tenant. */
  private static final class CountingSource implements BrokerCredentialSource {
    final AtomicInteger calls = new AtomicInteger();
    private final BrokerCredentials creds;

    CountingSource(BrokerCredentials creds) {
      this.creds = creds;
    }

    @Override
    public BrokerCredentials resolve(String tenantId, String provider) {
      calls.incrementAndGet();
      return creds;
    }
  }

  private AlpacaBrokerClientRegistry registry(BrokerCredentialSource source, String brokerImpl) {
    return new AlpacaBrokerClientRegistry(source, builder, mapper, meterRegistry, brokerImpl);
  }

  /**
   * A cred set pointed at the MockWebServer. The broker.impl suffix used in these tests is {@code
   * alpaca-x} (neither {@code -paper} nor {@code -live}), so the base-url mode-coherence branch is
   * inert and the MockWebServer URL can be used directly.
   */
  private BrokerCredentials credsPaper(String expectedAccountId) {
    return new BrokerCredentials("key", "secret", baseUrl, "", expectedAccountId);
  }

  @Test
  void cachesPerKey_buildOncePerTenant() {
    // blank expected → no account read; the alpaca-x impl suffix triggers neither the paper nor
    // live
    // base-url coherence branch, so the build succeeds with only the cred-presence check.
    CountingSource source = new CountingSource(credsPaper(""));
    AlpacaBrokerClientRegistry reg = registry(source, "alpaca-x");

    OptionsBroker b1 = reg.brokerFor("dev", "alpaca");
    OptionsBroker b2 = reg.brokerFor("dev", "alpaca");

    assertThat(b1).isSameAs(b2);
    assertThat(source.calls.get()).isEqualTo(1);
  }

  @Test
  void distinctClientsPerTenant() {
    CountingSource source = new CountingSource(credsPaper(""));
    AlpacaBrokerClientRegistry reg = registry(source, "alpaca-x");

    OptionsBroker dev = reg.brokerFor("dev", "alpaca");
    OptionsBroker acme = reg.brokerFor("acme", "alpaca");

    assertThat(dev).isNotSameAs(acme);
    assertThat(source.calls.get()).isEqualTo(2);
  }

  @Test
  void envFallbackReturnsTodaysCredsForAnyTenant() {
    // The env-fallback source ignores tenantId; assert it is invoked with whatever tenant the
    // registry passes and that both tenants resolve to a usable broker.
    EnvFallbackBrokerCredentialSource envSource =
        new EnvFallbackBrokerCredentialSource(
            new AlpacaProperties(baseUrl, "key", "secret"), "", "");
    AlpacaBrokerClientRegistry reg = registry(envSource, "alpaca-x");

    assertThat(reg.brokerFor("dev", "alpaca")).isNotNull();
    assertThat(reg.brokerFor("acme", "alpaca")).isNotNull();
  }

  @Test
  void blankExpectedAccountId_buildsWithoutAccountRead() {
    CountingSource source = new CountingSource(credsPaper(""));
    AlpacaBrokerClientRegistry reg = registry(source, "alpaca-x");

    OptionsBroker b = reg.brokerFor("dev", "alpaca");

    assertThat(b).isNotNull();
    // No /v2/account request was enqueued/needed: the server saw zero requests.
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void failsClosedOnAccountMismatch_noCachedEntry_secondCallReattempts() {
    // expected != the account the /v2/account read returns → build throws, nothing cached.
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("{\"account_number\":\"999999999\",\"equity\":\"1\",\"cash\":\"1\"}"));
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("{\"account_number\":\"999999999\",\"equity\":\"1\",\"cash\":\"1\"}"));

    CountingSource source = new CountingSource(credsPaper("847309116"));
    AlpacaBrokerClientRegistry reg = registry(source, "alpaca-x");

    assertThatThrownBy(() -> reg.brokerFor("dev", "alpaca"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("mismatch");

    // No entry cached → a 2nd call re-attempts the build (resolve called again, server hit again).
    assertThatThrownBy(() -> reg.brokerFor("dev", "alpaca"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(source.calls.get()).isEqualTo(2);
  }

  @Test
  void failsClosedOnModeIncoherence_paperImplLiveBaseUrl() {
    // paper impl + a non-paper base-url → coherence check throws before any account read.
    BrokerCredentials liveBase =
        new BrokerCredentials("key", "secret", "https://api.alpaca.markets", "", "");
    CountingSource source = new CountingSource(liveBase);
    AlpacaBrokerClientRegistry reg = registry(source, "alpaca-paper");

    assertThatThrownBy(() -> reg.brokerFor("dev", "alpaca"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("paper endpoint");
    assertThat(server.getRequestCount()).isZero();
  }

  // ---- P4-c-b-2: config-declared-account cross-check ----

  private void enqueueAccount(String accountNumber) {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"account_number\":\"" + accountNumber + "\",\"equity\":\"1\",\"cash\":\"1\"}"));
  }

  @Test
  void crossCheck_matchingDeclaredAccount_placesOk() {
    enqueueAccount("847309116");
    CountingSource source = new CountingSource(credsPaper("847309116"));
    AlpacaBrokerClientRegistry reg = registry(source, "alpaca-x");

    // declared == creds-authenticated account → no throw, broker returned.
    assertThat(reg.brokerFor("dev", "alpaca", "847309116")).isNotNull();
  }

  @Test
  void crossCheck_mismatchedDeclaredAccount_failsClosedNonRetryable() {
    // creds authenticate (and /v2/account confirms) 847309116, but the intent declares a DIFFERENT
    // account → the order must not route to the wrong account.
    enqueueAccount("847309116");
    CountingSource source = new CountingSource(credsPaper("847309116"));
    AlpacaBrokerClientRegistry reg = registry(source, "alpaca-x");

    assertThatThrownBy(() -> reg.brokerFor("dev", "alpaca", "999999999"))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            f -> {
              assertThat(f.getType()).isEqualTo("AccountMismatchError");
              assertThat(f.isNonRetryable()).isTrue();
            })
        .hasMessageContaining("847309116")
        .hasMessageContaining("999999999");
  }

  @Test
  void crossCheck_blankDeclared_skips() {
    enqueueAccount("847309116");
    CountingSource source = new CountingSource(credsPaper("847309116"));
    AlpacaBrokerClientRegistry reg = registry(source, "alpaca-x");

    // Today's tenants declare no broker_account_id → null/blank declared → cross-check skipped.
    assertThat(reg.brokerFor("dev", "alpaca", null)).isNotNull();
    assertThat(reg.brokerFor("dev", "alpaca", "  ")).isNotNull();
  }

  @Test
  void crossCheck_blankExpected_skipsEvenWhenDeclaredSet() {
    // Live env-paper shape: creds carry a blank expected-account-id (P2 disabled), so even a
    // non-blank declared account does NOT fail — behavior-preserving. No /v2/account read happens.
    CountingSource source = new CountingSource(credsPaper(""));
    AlpacaBrokerClientRegistry reg = registry(source, "alpaca-x");

    assertThat(reg.brokerFor("dev", "alpaca", "847309116")).isNotNull();
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void crossCheck_runsPerCall_notPerBuild_cannotBeSkippedByAReadCaller() {
    // The load-bearing property: a read-caller (2-arg, no declared account) warms the cache FIRST;
    // a later order (3-arg) with a mismatched declared account must STILL fail closed — the check
    // runs on every call against the cached expectedAccountId, not only at build time.
    enqueueAccount("847309116");
    CountingSource source = new CountingSource(credsPaper("847309116"));
    AlpacaBrokerClientRegistry reg = registry(source, "alpaca-x");

    // Read-path warm-up (e.g. AccountSnapshot via the 2-arg) builds + caches with no cross-check.
    assertThat(reg.brokerFor("dev", "alpaca")).isNotNull();

    // Order path on the SAME cached entry with a mismatched declared account → fail closed.
    assertThatThrownBy(() -> reg.brokerFor("dev", "alpaca", "999999999"))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            f -> assertThat(f.getType()).isEqualTo("AccountMismatchError"));
    // Only the build read /v2/account once; the mismatched call hit the cache (no rebuild).
    assertThat(server.getRequestCount()).isEqualTo(1);
    assertThat(source.calls.get()).isEqualTo(1);
  }

  @Test
  void unknownProviderIsNonRetryable() {
    CountingSource source = new CountingSource(credsPaper(""));
    AlpacaBrokerClientRegistry reg = registry(source, "alpaca-x");

    assertThatThrownBy(() -> reg.brokerFor("dev", "tradier"))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            f -> {
              assertThat(f.getType()).isEqualTo("InvalidBrokerTargetError");
              assertThat(f.isNonRetryable()).isTrue();
            });
    // Never resolved creds for an unservable provider.
    assertThat(source.calls.get()).isZero();
  }
}
