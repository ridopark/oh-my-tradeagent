package com.ohmytradeagent.exec.broker.alpaca;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.exec.broker.BrokerClientRegistry;
import com.ohmytradeagent.exec.broker.BrokerCredentialSource;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import io.temporal.failure.ApplicationFailure;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * P4-a: the boot account-identity probe is now a REGISTRY WARM-UP. It must (1) still reject a live
 * impl with a blank declared account BEFORE warming, (2) drive {@code registry.brokerFor(tenant,
 * "alpaca")} so the build's account assertion runs at boot, and (3) propagate any build failure
 * (mismatch / unreachable) out of {@code run()} so boot aborts. The detailed read-retry + assertion
 * behavior is covered by {@link BrokerAccountIdentityVerifierTest}.
 *
 * <p>db-creds soft-boot: under {@code broker.creds.source=db} the bootstrap credential row is
 * written POST-boot via the admin endpoint, so a not-yet-written row surfaces as a non-retryable
 * {@link BrokerCredentialSource#UNAVAILABLE_TYPE} failure out of {@code brokerFor}. Aborting boot
 * on that creates a deadlock (the write endpoint only exists on a running pod), so the probe must
 * SOFT boot — log a warning and continue — strictly for {@code source=db} + UNAVAILABLE_TYPE. Every
 * other failure (mismatch, unreachable, env/file missing-creds) must STILL abort boot.
 */
class AlpacaAccountIdentityProbeTest {

  private static DefaultApplicationArguments noArgs() {
    return new DefaultApplicationArguments();
  }

  private static ApplicationFailure unavailable(String msg) {
    return ApplicationFailure.newNonRetryableFailure(msg, BrokerCredentialSource.UNAVAILABLE_TYPE);
  }

  @Test
  void liveImplWarmsRegistryForBootstrapTenant() throws Exception {
    BrokerClientRegistry registry = mock(BrokerClientRegistry.class);
    when(registry.brokerFor("dev", "alpaca")).thenReturn(mock(OptionsBroker.class));

    AlpacaAccountIdentityProbe probe =
        new AlpacaAccountIdentityProbe(registry, "alpaca-live", "847309116", "dev", "env");

    assertThatCode(() -> probe.run(noArgs())).doesNotThrowAnyException();
    verify(registry).brokerFor("dev", "alpaca");
  }

  @Test
  void liveImplBuildFailurePropagatesAndAbortsBoot() {
    // A registry build that fails closed (account mismatch / unreachable surfaces as an
    // IllegalStateException out of brokerFor) must propagate from run() so Spring boot aborts.
    BrokerClientRegistry registry = mock(BrokerClientRegistry.class);
    when(registry.brokerFor("dev", "alpaca"))
        .thenThrow(
            new IllegalStateException(
                "broker account mismatch: keys authenticate account=999999999 but"
                    + " EXPECTED_ALPACA_ACCOUNT_ID=847309116"));

    AlpacaAccountIdentityProbe probe =
        new AlpacaAccountIdentityProbe(registry, "alpaca-live", "847309116", "dev", "env");

    assertThatThrownBy(() -> probe.run(noArgs()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("mismatch")
        .hasMessageContaining("847309116");
  }

  @Test
  void liveImplBlankExpectedThrowsBeforeWarmup() {
    BrokerClientRegistry registry = mock(BrokerClientRegistry.class);

    AlpacaAccountIdentityProbe probe =
        new AlpacaAccountIdentityProbe(registry, "alpaca-live", "", "dev", "env");

    assertThatThrownBy(() -> probe.run(noArgs()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("EXPECTED_ALPACA_ACCOUNT_ID");
    // A live worker with no declared account must abort BEFORE touching the registry.
    verify(registry, never()).brokerFor(eq("dev"), eq("alpaca"));
  }

  @Test
  void paperImplBlankExpectedStillWarmsRegistry() throws Exception {
    // Paper with no declared account: assertion disabled, but the registry is still warmed so the
    // mode-coherence + cred guards run at boot.
    BrokerClientRegistry registry = mock(BrokerClientRegistry.class);
    when(registry.brokerFor("dev", "alpaca")).thenReturn(mock(OptionsBroker.class));

    AlpacaAccountIdentityProbe probe =
        new AlpacaAccountIdentityProbe(registry, "alpaca-paper", "", "dev", "env");

    assertThatCode(() -> probe.run(noArgs())).doesNotThrowAnyException();
    verify(registry).brokerFor("dev", "alpaca");
  }

  @Test
  void dbSourceUnavailableSoftBootsAndContinues() throws Exception {
    // source=db: the bootstrap credential row is written post-boot. A not-yet-written row surfaces
    // as a non-retryable UNAVAILABLE_TYPE failure out of brokerFor — the probe must NOT abort boot.
    BrokerClientRegistry registry = mock(BrokerClientRegistry.class);
    when(registry.brokerFor("dev", "alpaca")).thenThrow(unavailable("no credential row"));

    AlpacaAccountIdentityProbe probe =
        new AlpacaAccountIdentityProbe(registry, "alpaca-paper", "", "dev", "db");

    assertThatCode(() -> probe.run(noArgs())).doesNotThrowAnyException();
    verify(registry).brokerFor("dev", "alpaca");
  }

  @Test
  void dbSourceDifferentApplicationFailureStillAbortsBoot() {
    // source=db but a DIFFERENT ApplicationFailure type (e.g. an account mismatch surfaced as an
    // ApplicationFailure) is fatal — only UNAVAILABLE_TYPE is soft.
    BrokerClientRegistry registry = mock(BrokerClientRegistry.class);
    when(registry.brokerFor("dev", "alpaca"))
        .thenThrow(
            ApplicationFailure.newNonRetryableFailure("account mismatch", "AccountMismatch"));

    AlpacaAccountIdentityProbe probe =
        new AlpacaAccountIdentityProbe(registry, "alpaca-paper", "", "dev", "db");

    assertThatThrownBy(() -> probe.run(noArgs()))
        .isInstanceOf(ApplicationFailure.class)
        .hasMessageContaining("account mismatch");
  }

  @Test
  void dbSourceNonApplicationFailureStillAbortsBoot() {
    // source=db but a non-ApplicationFailure RuntimeException (e.g. unreachable surfaced as an
    // IllegalStateException) is fatal — the soft path only catches ApplicationFailure.
    BrokerClientRegistry registry = mock(BrokerClientRegistry.class);
    when(registry.brokerFor("dev", "alpaca"))
        .thenThrow(new IllegalStateException("broker unreachable"));

    AlpacaAccountIdentityProbe probe =
        new AlpacaAccountIdentityProbe(registry, "alpaca-paper", "", "dev", "db");

    assertThatThrownBy(() -> probe.run(noArgs()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unreachable");
  }

  @Test
  void envSourceUnavailableStillAbortsBoot() {
    // source=env (default): env/file must have creds at boot. UNAVAILABLE_TYPE is fatal here — the
    // soft path is db-only.
    BrokerClientRegistry registry = mock(BrokerClientRegistry.class);
    when(registry.brokerFor("dev", "alpaca")).thenThrow(unavailable("no env credential"));

    AlpacaAccountIdentityProbe probe =
        new AlpacaAccountIdentityProbe(registry, "alpaca-paper", "", "dev", "env");

    assertThatThrownBy(() -> probe.run(noArgs()))
        .isInstanceOf(ApplicationFailure.class)
        .hasMessageContaining("no env credential");
  }

  @Test
  void fileSourceUnavailableStillAbortsBoot() {
    // source=file: file creds must be mounted at boot. UNAVAILABLE_TYPE is fatal here too.
    BrokerClientRegistry registry = mock(BrokerClientRegistry.class);
    when(registry.brokerFor("dev", "alpaca")).thenThrow(unavailable("no file credential"));

    AlpacaAccountIdentityProbe probe =
        new AlpacaAccountIdentityProbe(registry, "alpaca-paper", "", "dev", "file");

    assertThatThrownBy(() -> probe.run(noArgs()))
        .isInstanceOf(ApplicationFailure.class)
        .hasMessageContaining("no file credential");
  }
}
