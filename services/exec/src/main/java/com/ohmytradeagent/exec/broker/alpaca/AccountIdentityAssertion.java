package com.ohmytradeagent.exec.broker.alpaca;

/**
 * Phase P2 live-safety seam: asserts that the brokerage account the API keys actually authenticate
 * (read off {@code /v2/account} {@code account_number}) matches the operator-declared account. A
 * mismatch means the live worker is holding keys for the WRONG account — refusing to trade is the
 * only safe outcome on a real-money path.
 *
 * <p>Dependency-free + pure so it unit-tests trivially and so P4 can reuse it per-(tenant, account)
 * without dragging in Spring/broker wiring.
 *
 * <p>Semantics:
 *
 * <ul>
 *   <li>blank/null {@code expected} → no-op (assertion disabled; paper / back-compat).
 *   <li>blank/null {@code actual} (with a non-blank expected) → throw: the account read returned no
 *       identity, so we cannot prove we are on the right account → fail closed.
 *   <li>{@code !actual.equals(expected)} → throw, naming BOTH numbers for triage.
 * </ul>
 */
public final class AccountIdentityAssertion {

  private AccountIdentityAssertion() {}

  /**
   * Throws {@link IllegalStateException} unless {@code actual} matches the operator-declared {@code
   * expected} account. A blank/null {@code expected} disables the check.
   */
  public static void assertMatches(String actual, String expected) {
    if (expected == null || expected.isBlank()) {
      return;
    }
    if (actual == null || actual.isBlank()) {
      throw new IllegalStateException(
          "cannot verify broker account identity: account read returned no account_number");
    }
    if (!actual.equals(expected)) {
      throw new IllegalStateException(
          "broker account mismatch: keys authenticate account="
              + actual
              + " but EXPECTED_ALPACA_ACCOUNT_ID="
              + expected
              + " — refusing to trade the wrong account");
    }
  }
}
