package com.ohmytradeagent.exec.broker.alpaca;

import com.ohmytradeagent.exec.broker.OptionsBroker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Phase P2 live-safety: at boot of an Alpaca worker, prove the API keys authenticate the
 * operator-declared brokerage account before the worker starts trading. Wrong-account keys on the
 * real-money path are catastrophic, so this fails closed: an {@link ApplicationRunner} that throws
 * aborts Spring startup → k8s crashloops the pod (the correct signal that the deployment is
 * misconfigured / the account is unreachable).
 *
 * <p>Two distinct failure modes, kept distinguishable for triage:
 *
 * <ul>
 *   <li><b>mismatch</b> (permanent) — keys authenticate a DIFFERENT account than declared. Never
 *       retried; {@link AccountIdentityAssertion#assertMatches} throws and propagates immediately.
 *   <li><b>unreachable</b> (transient) — the account read itself failed (network blip). Retried a
 *       bounded number of times so a 2-3s Alpaca hiccup self-heals; if every attempt fails, the
 *       last exception propagates and boot aborts.
 * </ul>
 *
 * <p>Activated only for {@code alpaca-*} impls (mirrors {@link AlpacaConfig}). A {@code -live} impl
 * with a blank {@code EXPECTED_ALPACA_ACCOUNT_ID} is rejected at boot (a live worker must declare
 * its account); a {@code -paper} impl with it blank is a no-op (back-compat).
 */
@Component
@ConditionalOnExpression("'${broker.impl:}'.startsWith('alpaca-')")
public class AlpacaAccountIdentityProbe implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(AlpacaAccountIdentityProbe.class);

  /** Bounded retry budget for the TRANSIENT account-read; a mismatch is never retried. */
  private static final int MAX_ATTEMPTS = 3;

  private static final long BACKOFF_MS = 500L;

  private final OptionsBroker broker;
  private final String brokerImpl;
  private final String expectedAccount;

  public AlpacaAccountIdentityProbe(
      OptionsBroker broker,
      @Value("${broker.impl:}") String brokerImpl,
      @Value("${EXPECTED_ALPACA_ACCOUNT_ID:}") String expectedAccount) {
    this.broker = broker;
    this.brokerImpl = brokerImpl;
    this.expectedAccount = expectedAccount;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    boolean isLive = brokerImpl.endsWith("-live");
    boolean expectedBlank = expectedAccount == null || expectedAccount.isBlank();

    if (isLive && expectedBlank) {
      throw new IllegalStateException(
          "broker.impl="
              + brokerImpl
              + " (live) requires EXPECTED_ALPACA_ACCOUNT_ID to be set — refusing to boot a live"
              + " worker without a declared account");
    }

    if (expectedBlank) {
      // Paper (or any non-live impl) with no declared account: assertion disabled, back-compat.
      return;
    }

    String accountNumber = fetchAccountNumberWithRetry();
    // Mismatch is PERMANENT: this throws and propagates immediately (never retried above).
    AccountIdentityAssertion.assertMatches(accountNumber, expectedAccount);
    log.info(
        "broker account identity verified: account={} matches EXPECTED_ALPACA_ACCOUNT_ID",
        accountNumber);
  }

  /**
   * Reads {@code /v2/account} {@code account_number} with bounded retry on TRANSIENT failure. Only
   * the read is retried — the caller asserts identity on the returned value, and a mismatch (a
   * permanent condition) is not a read failure so it never reaches this method's retry loop. If
   * every attempt throws, the last exception is rethrown so boot fails closed.
   */
  private String fetchAccountNumberWithRetry() throws Exception {
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
            + " attempts (broker.impl="
            + brokerImpl
            + ") — refusing to boot",
        last);
  }
}
