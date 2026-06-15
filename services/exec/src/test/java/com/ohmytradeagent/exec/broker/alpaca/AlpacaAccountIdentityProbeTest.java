package com.ohmytradeagent.exec.broker.alpaca;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.exec.broker.BrokerClientRegistry;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * P4-a: the boot account-identity probe is now a REGISTRY WARM-UP. It must (1) still reject a live
 * impl with a blank declared account BEFORE warming, (2) drive {@code registry.brokerFor(tenant,
 * "alpaca")} so the build's account assertion runs at boot, and (3) propagate any build failure
 * (mismatch / unreachable) out of {@code run()} so boot aborts. The detailed read-retry + assertion
 * behavior is covered by {@link BrokerAccountIdentityVerifierTest}.
 */
class AlpacaAccountIdentityProbeTest {

  private static DefaultApplicationArguments noArgs() {
    return new DefaultApplicationArguments();
  }

  @Test
  void liveImplWarmsRegistryForBootstrapTenant() throws Exception {
    BrokerClientRegistry registry = mock(BrokerClientRegistry.class);
    when(registry.brokerFor("dev", "alpaca")).thenReturn(mock(OptionsBroker.class));

    AlpacaAccountIdentityProbe probe =
        new AlpacaAccountIdentityProbe(registry, "alpaca-live", "847309116", "dev");

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
        new AlpacaAccountIdentityProbe(registry, "alpaca-live", "847309116", "dev");

    assertThatThrownBy(() -> probe.run(noArgs()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("mismatch")
        .hasMessageContaining("847309116");
  }

  @Test
  void liveImplBlankExpectedThrowsBeforeWarmup() {
    BrokerClientRegistry registry = mock(BrokerClientRegistry.class);

    AlpacaAccountIdentityProbe probe =
        new AlpacaAccountIdentityProbe(registry, "alpaca-live", "", "dev");

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
        new AlpacaAccountIdentityProbe(registry, "alpaca-paper", "", "dev");

    assertThatCode(() -> probe.run(noArgs())).doesNotThrowAnyException();
    verify(registry).brokerFor("dev", "alpaca");
  }
}
