package com.ohmytradeagent.exec.broker.alpaca;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Phase P2: pure account-identity assertion used by the live-boot probe (and reused by P4). */
class AccountIdentityAssertionTest {

  @Test
  void noOpWhenExpectedBlank() {
    assertThatCode(() -> AccountIdentityAssertion.assertMatches("847309116", ""))
        .doesNotThrowAnyException();
    assertThatCode(() -> AccountIdentityAssertion.assertMatches(null, null))
        .doesNotThrowAnyException();
  }

  @Test
  void throwsWhenActualNullButExpectedSet() {
    assertThatThrownBy(() -> AccountIdentityAssertion.assertMatches(null, "847309116"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no account_number");
  }

  @Test
  void throwsOnMismatchNamingBothNumbers() {
    assertThatThrownBy(() -> AccountIdentityAssertion.assertMatches("999999999", "847309116"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("999999999")
        .hasMessageContaining("847309116");
  }

  @Test
  void passesOnMatch() {
    assertThatCode(() -> AccountIdentityAssertion.assertMatches("847309116", "847309116"))
        .doesNotThrowAnyException();
  }
}
