package com.ohmytradeagent.exec.broker.alpaca;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.exec.broker.OptionsBroker;
import com.ohmytradeagent.exec.broker.OptionsBroker.AccountSummary;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * Phase P2: the live-boot account-identity probe. Verifies fail-closed behavior (mismatch /
 * unreachable / missing declared account abort boot) and that the transient-read retry self-heals a
 * blip while NEVER retrying a permanent mismatch.
 */
class AlpacaAccountIdentityProbeTest {

  private static AccountSummary acct(String number) {
    return new AccountSummary(BigDecimal.ZERO, BigDecimal.ZERO, number);
  }

  private static DefaultApplicationArguments noArgs() {
    return new DefaultApplicationArguments();
  }

  @Test
  void liveImplMatchingAccountDoesNotThrow() {
    OptionsBroker broker = mock(OptionsBroker.class);
    when(broker.getAccount()).thenReturn(acct("847309116"));

    AlpacaAccountIdentityProbe probe =
        new AlpacaAccountIdentityProbe(broker, "alpaca-live", "847309116");

    assertThatCode(() -> probe.run(noArgs())).doesNotThrowAnyException();
  }

  @Test
  void liveImplMismatchedAccountThrowsAndDoesNotRetry() {
    OptionsBroker broker = mock(OptionsBroker.class);
    when(broker.getAccount()).thenReturn(acct("999999999"));

    AlpacaAccountIdentityProbe probe =
        new AlpacaAccountIdentityProbe(broker, "alpaca-live", "847309116");

    assertThatThrownBy(() -> probe.run(noArgs()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("mismatch")
        .hasMessageContaining("999999999")
        .hasMessageContaining("847309116");
    // Permanent mismatch must not be retried: exactly one account read.
    verify(broker, times(1)).getAccount();
  }

  @Test
  void liveImplBlankExpectedThrows() {
    OptionsBroker broker = mock(OptionsBroker.class);

    AlpacaAccountIdentityProbe probe = new AlpacaAccountIdentityProbe(broker, "alpaca-live", "");

    assertThatThrownBy(() -> probe.run(noArgs()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("EXPECTED_ALPACA_ACCOUNT_ID");
  }

  @Test
  void paperImplBlankExpectedIsNoOp() {
    OptionsBroker broker = mock(OptionsBroker.class);

    AlpacaAccountIdentityProbe probe = new AlpacaAccountIdentityProbe(broker, "alpaca-paper", "");

    assertThatCode(() -> probe.run(noArgs())).doesNotThrowAnyException();
    verify(broker, times(0)).getAccount();
  }

  @Test
  void liveImplAccountReadAlwaysFailsThrowsAfterBoundedRetries() {
    OptionsBroker broker = mock(OptionsBroker.class);
    when(broker.getAccount()).thenThrow(new RuntimeException("alpaca unreachable"));

    AlpacaAccountIdentityProbe probe =
        new AlpacaAccountIdentityProbe(broker, "alpaca-live", "847309116");

    assertThatThrownBy(() -> probe.run(noArgs()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot reach broker account endpoint");
    // Bounded retry: read attempted MAX_ATTEMPTS (3) times.
    verify(broker, times(3)).getAccount();
  }

  @Test
  void liveImplTransientThenSuccessSucceeds() {
    OptionsBroker broker = mock(OptionsBroker.class);
    when(broker.getAccount()).thenThrow(new RuntimeException("blip")).thenReturn(acct("847309116"));

    AlpacaAccountIdentityProbe probe =
        new AlpacaAccountIdentityProbe(broker, "alpaca-live", "847309116");

    assertThatCode(() -> probe.run(noArgs())).doesNotThrowAnyException();
    verify(broker, times(2)).getAccount();
  }
}
