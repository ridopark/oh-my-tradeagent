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

/**
 * The shared P2 account-identity verification used by both the registry build and the boot warm-up.
 * Pins fail-closed behavior (mismatch / unreachable throw) and that the transient-read retry
 * self-heals a blip while NEVER retrying a permanent mismatch. Lifted from the pre-P4-a
 * AlpacaAccountIdentityProbeTest.
 */
class BrokerAccountIdentityVerifierTest {

  private static AccountSummary acct(String number) {
    return new AccountSummary(BigDecimal.ZERO, BigDecimal.ZERO, number);
  }

  @Test
  void matchingAccountDoesNotThrow() throws Exception {
    OptionsBroker broker = mock(OptionsBroker.class);
    when(broker.getAccount()).thenReturn(acct("847309116"));

    assertThatCode(() -> BrokerAccountIdentityVerifier.verify(broker, "847309116", "test"))
        .doesNotThrowAnyException();
    verify(broker, times(1)).getAccount();
  }

  @Test
  void mismatchedAccountThrowsAndDoesNotRetry() {
    OptionsBroker broker = mock(OptionsBroker.class);
    when(broker.getAccount()).thenReturn(acct("999999999"));

    assertThatThrownBy(() -> BrokerAccountIdentityVerifier.verify(broker, "847309116", "test"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("mismatch")
        .hasMessageContaining("999999999")
        .hasMessageContaining("847309116");
    // Permanent mismatch must not be retried: exactly one account read.
    verify(broker, times(1)).getAccount();
  }

  @Test
  void blankExpectedIsNoOp() throws Exception {
    OptionsBroker broker = mock(OptionsBroker.class);

    assertThatCode(() -> BrokerAccountIdentityVerifier.verify(broker, "", "test"))
        .doesNotThrowAnyException();
    verify(broker, times(0)).getAccount();
  }

  @Test
  void accountReadAlwaysFailsThrowsAfterBoundedRetries() {
    OptionsBroker broker = mock(OptionsBroker.class);
    when(broker.getAccount()).thenThrow(new RuntimeException("alpaca unreachable"));

    assertThatThrownBy(() -> BrokerAccountIdentityVerifier.verify(broker, "847309116", "test"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot reach broker account endpoint");
    // Bounded retry: read attempted MAX_ATTEMPTS (3) times.
    verify(broker, times(3)).getAccount();
  }

  @Test
  void transientThenSuccessSucceeds() throws Exception {
    OptionsBroker broker = mock(OptionsBroker.class);
    when(broker.getAccount()).thenThrow(new RuntimeException("blip")).thenReturn(acct("847309116"));

    assertThatCode(() -> BrokerAccountIdentityVerifier.verify(broker, "847309116", "test"))
        .doesNotThrowAnyException();
    verify(broker, times(2)).getAccount();
  }
}
