package com.ohmytradeagent.exec.broker.alpaca;

import com.ohmytradeagent.exec.broker.OptionsBroker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared P2 account-identity verification: reads {@code getAccount().accountNumber()} with bounded
 * retry on TRANSIENT failure, then asserts it matches the operator-declared {@code expected}. A
 * blank {@code expected} disables the check (paper / back-compat).
 *
 * <p>Lifted out of {@code AlpacaAccountIdentityProbe} so both the boot warm-up AND the registry's
 * per-key build run the IDENTICAL transient-read-retry + assertion (so transient-read parity holds
 * and a mismatch is never retried). Dependency-free + pure (no Spring).
 *
 * <p>Failure modes (kept distinguishable for triage):
 *
 * <ul>
 *   <li><b>mismatch</b> (permanent) — keys authenticate a DIFFERENT account than declared. Never
 *       retried; {@link AccountIdentityAssertion#assertMatches} throws and propagates immediately.
 *   <li><b>unreachable</b> (transient) — the account read itself failed. Retried a bounded number
 *       of times; if every attempt fails, the last exception propagates.
 * </ul>
 */
public final class BrokerAccountIdentityVerifier {

  private static final Logger log = LoggerFactory.getLogger(BrokerAccountIdentityVerifier.class);

  /** Bounded retry budget for the TRANSIENT account-read; a mismatch is never retried. */
  static final int MAX_ATTEMPTS = 3;

  static final long BACKOFF_MS = 500L;

  private BrokerAccountIdentityVerifier() {}

  /**
   * Reads the broker account number (bounded retry) and asserts it matches {@code expected},
   * returning the verified account number. No-op when {@code expected} is blank — returns {@code
   * null} (no probe ran, no account to report). Throws {@link IllegalStateException} on mismatch
   * (permanent) or after exhausting the transient-read retry budget. {@code context} names the call
   * site (e.g. the broker.impl or the registry key) for triage messages.
   *
   * @return the verified {@code /v2/account} number when {@code expected} is non-blank, else {@code
   *     null} (probe disabled). Never a key/secret — an account identifier only.
   */
  public static String verify(OptionsBroker broker, String expected, String context)
      throws InterruptedException {
    if (expected == null || expected.isBlank()) {
      // assertion disabled (paper / back-compat) — no probe, so no account number to report.
      return null;
    }
    String accountNumber = fetchAccountNumberWithRetry(broker, context);
    // Mismatch is PERMANENT: this throws and propagates immediately (never retried above).
    AccountIdentityAssertion.assertMatches(accountNumber, expected);
    log.info(
        "broker account identity verified: account={} matches expected ({})",
        accountNumber,
        context);
    return accountNumber;
  }

  private static String fetchAccountNumberWithRetry(OptionsBroker broker, String context)
      throws InterruptedException {
    Exception last = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        return broker.getAccount().accountNumber();
      } catch (Exception e) {
        last = e;
        log.warn(
            "broker account read attempt {}/{} failed (transient): {}",
            attempt,
            MAX_ATTEMPTS,
            e.toString());
        if (attempt < MAX_ATTEMPTS) {
          Thread.sleep(BACKOFF_MS);
        }
      }
    }
    throw new IllegalStateException(
        "cannot reach broker account endpoint to verify identity after "
            + MAX_ATTEMPTS
            + " attempts ("
            + context
            + ") — refusing to boot",
        last);
  }
}
