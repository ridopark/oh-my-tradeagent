package com.ohmytradeagent.exec.broker.alpaca;

import com.ohmytradeagent.exec.broker.BrokerClientRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Phase P2 live-safety, P4-a registry warm-up: at boot of an Alpaca worker, prove the API keys
 * authenticate the operator-declared brokerage account before the worker starts trading.
 * Wrong-account keys on the real-money path are catastrophic, so this fails closed: an {@link
 * ApplicationRunner} that throws aborts Spring startup → k8s crashloops the pod (the correct signal
 * that the deployment is misconfigured / the account is unreachable).
 *
 * <p>P4-a: the account-identity read + assertion + bounded transient-retry now live in the {@link
 * BrokerClientRegistry} build ({@link BrokerAccountIdentityVerifier}). This runner simply WARMS the
 * registry for the configured bootstrap tenant — {@code registry.brokerFor(tenant, "alpaca")} — so
 * the build runs at boot and a mismatch/unreachable throws here, aborting startup exactly as
 * before. Account identity is therefore NOT lazy-only.
 *
 * <p>Under the env-fallback credential source the assertion is tenant-independent (same creds →
 * same account), so the crashloop-on-misconfig guarantee holds regardless of the warm-up tenant
 * key. P4-b (per-tenant creds) will warm each live tenant explicitly.
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
 * its account); a {@code -paper} impl with it blank warms the registry with the account assertion
 * disabled (back-compat).
 */
@Component
@ConditionalOnExpression("'${broker.impl:}'.startsWith('alpaca-')")
public class AlpacaAccountIdentityProbe implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(AlpacaAccountIdentityProbe.class);

  /** Provider this warm-up bootstraps; the exec pod is single-provider in P4-a. */
  private static final String PROVIDER = "alpaca";

  private final BrokerClientRegistry registry;
  private final String brokerImpl;
  private final String expectedAccount;
  private final String bootstrapTenant;

  public AlpacaAccountIdentityProbe(
      BrokerClientRegistry registry,
      @Value("${broker.impl:}") String brokerImpl,
      @Value("${EXPECTED_ALPACA_ACCOUNT_ID:}") String expectedAccount,
      @Value("${EXEC_BOOTSTRAP_TENANT_ID:dev}") String bootstrapTenant) {
    this.registry = registry;
    this.brokerImpl = brokerImpl;
    this.expectedAccount = expectedAccount;
    this.bootstrapTenant = bootstrapTenant;
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

    // Warm the registry for the bootstrap tenant. The build runs the mode-coherence check + the P2
    // account-identity assertion (with bounded transient-read retry); a mismatch/unreachable throws
    // out of here and aborts boot (fail closed). A blank expected is a no-op assertion (paper).
    registry.brokerFor(bootstrapTenant, PROVIDER);
    if (!expectedBlank) {
      log.info(
          "broker account identity verified at boot via registry warm-up (tenant={})",
          bootstrapTenant);
    }
  }
}
