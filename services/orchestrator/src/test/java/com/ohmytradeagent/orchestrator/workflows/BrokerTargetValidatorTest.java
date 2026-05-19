package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Directly exercises the {@link BrokerTargetValidator#isValid(String)} contract.
 *
 * <p>{@link ExecActivitiesFactoryTest} covers the broker_target -&gt; task-queue mapping but
 * pre-filters {@code null} before reaching the validator, so the {@code isValid(null)} branch is
 * never exercised there. This class locks the {@link BrokerTargetValidator#VALID_TARGET} regex
 * contract ({@code ^(paper|live|[a-z]+-(paper|live))$}) with focused unit tests.
 *
 * <p>Issue #102.
 */
class BrokerTargetValidatorTest {

  // --- Required cases from the issue title ---------------------------------------------------

  @Test
  void isValid_null_returnsFalse() {
    assertThat(BrokerTargetValidator.isValid(null)).isFalse();
  }

  @Test
  void isValid_emptyString_returnsFalse() {
    assertThat(BrokerTargetValidator.isValid("")).isFalse();
  }

  @Test
  void isValid_alpacaPaper_returnsTrue() {
    assertThat(BrokerTargetValidator.isValid("alpaca-paper")).isTrue();
  }

  @Test
  void isValid_paper_returnsTrue() {
    assertThat(BrokerTargetValidator.isValid("paper")).isTrue();
  }

  // --- Additional accepted forms (lock the regex contract) -----------------------------------

  @Test
  void isValid_live_returnsTrue() {
    assertThat(BrokerTargetValidator.isValid("live")).isTrue();
  }

  @Test
  void isValid_alpacaLive_returnsTrue() {
    assertThat(BrokerTargetValidator.isValid("alpaca-live")).isTrue();
  }

  @Test
  void isValid_tradierPaper_returnsTrue() {
    assertThat(BrokerTargetValidator.isValid("tradier-paper")).isTrue();
  }

  @Test
  void isValid_tradierLive_returnsTrue() {
    assertThat(BrokerTargetValidator.isValid("tradier-live")).isTrue();
  }

  // --- Rejected forms (lock the regex contract) ----------------------------------------------

  @Test
  void isValid_mixedCasePaper_returnsFalse() {
    assertThat(BrokerTargetValidator.isValid("Paper")).isFalse();
  }

  @Test
  void isValid_uppercaseAlpacaPaper_returnsFalse() {
    assertThat(BrokerTargetValidator.isValid("Alpaca-Paper")).isFalse();
  }

  @Test
  void isValid_underscoreSeparator_returnsFalse() {
    assertThat(BrokerTargetValidator.isValid("alpaca_paper")).isFalse();
  }

  @Test
  void isValid_unknownEnvSuffix_returnsFalse() {
    assertThat(BrokerTargetValidator.isValid("alpaca-staging")).isFalse();
  }

  @Test
  void isValid_unknownEnvProd_returnsFalse() {
    assertThat(BrokerTargetValidator.isValid("alpaca-prod")).isFalse();
  }

  @Test
  void isValid_trailingWhitespace_returnsFalse() {
    assertThat(BrokerTargetValidator.isValid("paper ")).isFalse();
  }

  @Test
  void isValid_leadingWhitespace_returnsFalse() {
    assertThat(BrokerTargetValidator.isValid(" paper")).isFalse();
  }

  @Test
  void isValid_blankSpaces_returnsFalse() {
    assertThat(BrokerTargetValidator.isValid("  ")).isFalse();
  }

  @Test
  void isValid_pathTraversal_returnsFalse() {
    assertThat(BrokerTargetValidator.isValid("../paper")).isFalse();
  }

  @Test
  void isValid_digitsInProvider_returnsFalse() {
    // VALID_TARGET requires [a-z]+ for the provider segment — digits not allowed.
    assertThat(BrokerTargetValidator.isValid("broker1-paper")).isFalse();
  }

  @Test
  void isValid_emptyProviderSegment_returnsFalse() {
    // Leading hyphen would imply an empty provider — must be rejected.
    assertThat(BrokerTargetValidator.isValid("-paper")).isFalse();
  }

  @Test
  void isValid_trailingHyphenNoEnv_returnsFalse() {
    assertThat(BrokerTargetValidator.isValid("alpaca-")).isFalse();
  }
}
