package com.ohmytradeagent.exec.broker.alpaca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.exec.broker.BrokerCredentialSource;
import com.ohmytradeagent.exec.broker.BrokerCredentials;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.temporal.failure.ApplicationFailure;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Pins the P4-b-2 credential-rotation cache invalidation in {@link AlpacaBrokerClientRegistry}.
 *
 * <p>The fake source returns paper-host creds with a BLANK {@code expectedAccountId}, so {@code
 * build()} runs the mode-coherence check + the (no-op-on-blank) P2 assertion with ZERO network — it
 * counts {@code resolve} invocations as a proxy for "client built". The fingerprint is operator-
 * controlled, modelling a k8s Secret rotation.
 */
class AlpacaBrokerClientRegistryRotationTest {

  private static final String PAPER_HOST = "https://paper-api.alpaca.markets";

  /** Source whose fingerprint + resolve behavior the test drives; counts builds via resolve. */
  static final class FakeSource implements BrokerCredentialSource {
    volatile String fingerprint = "fp-1";
    volatile boolean failResolve = false;
    final AtomicInteger resolveCount = new AtomicInteger();

    @Override
    public BrokerCredentials resolve(String tenantId, String provider) {
      resolveCount.incrementAndGet();
      if (failResolve) {
        throw ApplicationFailure.newNonRetryableFailure(
            "rotated to bad creds", "BrokerCredentialsUnavailable");
      }
      return new BrokerCredentials("k", "s", PAPER_HOST, "", ""); // blank expected → verify no-op
    }

    @Override
    public String fingerprint(String tenantId, String provider) {
      return fingerprint;
    }
  }

  private AlpacaBrokerClientRegistry registry(FakeSource src) {
    return new AlpacaBrokerClientRegistry(
        src, RestClient.builder(), new ObjectMapper(), new SimpleMeterRegistry(), "alpaca-paper");
  }

  @Test
  void constantFingerprintBuildsOnceAndReturnsSameInstance() {
    // Criterion #1 / live-safety proof: a constant fingerprint (the env-source default) must never
    // rebuild — one build, same instance, regardless of how many times the key is resolved.
    FakeSource src = new FakeSource();
    AlpacaBrokerClientRegistry reg = registry(src);

    OptionsBroker first = reg.brokerFor("alice", "alpaca");
    for (int i = 0; i < 5; i++) {
      assertThat(reg.brokerFor("alice", "alpaca")).isSameAs(first);
    }
    assertThat(src.resolveCount).hasValue(1);
  }

  @Test
  void fingerprintChangeRebuildsWithNewClient() {
    // Criterion #2: a rotation (fingerprint delta) rebuilds → a NEW client reflecting new creds.
    FakeSource src = new FakeSource();
    AlpacaBrokerClientRegistry reg = registry(src);

    OptionsBroker before = reg.brokerFor("alice", "alpaca");
    src.fingerprint = "fp-2"; // operator rotated the mounted Secret
    OptionsBroker after = reg.brokerFor("alice", "alpaca");

    assertThat(after).isNotSameAs(before);
    assertThat(src.resolveCount).hasValue(2);
  }

  @Test
  void failedRebuildFailsClosedEvictsAndReattempts() {
    // Criterion #3 / halt: a rebuild that throws (rotated to bad creds) must NOT keep serving the
    // old client — it throws, caches nothing, and the next call re-attempts (never the stale one).
    FakeSource src = new FakeSource();
    AlpacaBrokerClientRegistry reg = registry(src);

    OptionsBroker good = reg.brokerFor("alice", "alpaca");
    assertThat(good).isNotNull();

    src.fingerprint = "fp-2";
    src.failResolve = true;

    assertThatThrownBy(() -> reg.brokerFor("alice", "alpaca"))
        .isInstanceOf(ApplicationFailure.class);
    // re-attempt (not a served stale client): resolve is invoked AGAIN and it still fails closed.
    int afterFirstFailure = src.resolveCount.get();
    assertThatThrownBy(() -> reg.brokerFor("alice", "alpaca"))
        .isInstanceOf(ApplicationFailure.class);
    assertThat(src.resolveCount.get()).isGreaterThan(afterFirstFailure);

    // recovery: the operator fixes the creds (same rotated fingerprint) → next call rebuilds clean.
    src.failResolve = false;
    OptionsBroker recovered = reg.brokerFor("alice", "alpaca");
    assertThat(recovered).isNotNull().isNotSameAs(good);
  }

  @Test
  void distinctTenantsGetDistinctClients() {
    FakeSource src = new FakeSource();
    AlpacaBrokerClientRegistry reg = registry(src);
    assertThat(reg.brokerFor("alice", "alpaca")).isNotSameAs(reg.brokerFor("bob", "alpaca"));
  }
}
