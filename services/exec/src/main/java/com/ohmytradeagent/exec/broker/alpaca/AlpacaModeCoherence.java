package com.ohmytradeagent.exec.broker.alpaca;

/**
 * Single source of truth for the Alpaca boot/build fail-fast guards, extracted VERBATIM from the
 * pre-P4-a {@code AlpacaConfig.alpacaRestClient} bean so both the (retained) config and the
 * registry's per-key build enforce identical coherence with identical {@link IllegalStateException}
 * messages.
 *
 * <p>Dependency-free + pure (like {@link AccountIdentityAssertion}) so it unit-tests trivially and
 * so the registry can call it per-(tenant, account) without dragging in Spring/broker wiring.
 *
 * <p>Guards (a misconfigured deployment must not silently boot and then 401 every order, and a
 * real-money build must never silently route to paper / a paper build to live):
 *
 * <ul>
 *   <li>blank {@code apiKeyId} / {@code apiSecretKey} → fail (missing creds).
 *   <li>{@code -live} impl pointed at a paper base URL → fail.
 *   <li>{@code -paper} impl NOT pointed at a paper base URL → fail.
 *   <li>{@code -live} impl whose WS URL still targets the paper trade-updates stream → fail.
 * </ul>
 */
public final class AlpacaModeCoherence {

  private AlpacaModeCoherence() {}

  /**
   * Asserts the credentials are present (blank/null {@code apiKeyId} / {@code apiSecretKey} fail
   * fast). Messages match the pre-P4-a {@code AlpacaConfig} verbatim.
   */
  public static void assertCredentialsPresent(String apiKeyId, String apiSecretKey) {
    if (isBlank(apiKeyId)) {
      throw new IllegalStateException(
          "broker.impl=alpaca-* requires APCA_API_KEY_ID; got blank/null. "
              + "Set the alpaca-credentials Secret in your deployment.");
    }
    if (isBlank(apiSecretKey)) {
      throw new IllegalStateException(
          "broker.impl=alpaca-* requires APCA_API_SECRET_KEY; got blank/null. "
              + "Set the alpaca-credentials Secret in your deployment.");
    }
  }

  /**
   * Asserts the broker mode is coherent. {@code brokerImpl} is the impl/target string whose suffix
   * encodes the mode (e.g. {@code "alpaca-live"} / {@code "alpaca-paper"}); only its {@code -live}
   * / {@code -paper} suffix is inspected. Throws {@link IllegalStateException} on any incoherence.
   */
  public static void assertCoherent(String brokerImpl, String baseUrl, String wsUrl) {
    if (brokerImpl == null) {
      brokerImpl = "";
    }
    boolean baseUrlIsPaper = baseUrl != null && baseUrl.contains("paper");
    if (brokerImpl.endsWith("-live") && baseUrlIsPaper) {
      throw new IllegalStateException(
          "broker.impl="
              + brokerImpl
              + " (live) must not target a paper endpoint; "
              + "alpaca.base-url="
              + baseUrl
              + ". Point it at the live host.");
    }
    if (brokerImpl.endsWith("-paper") && !baseUrlIsPaper) {
      throw new IllegalStateException(
          "broker.impl="
              + brokerImpl
              + " (paper) must target a paper endpoint; "
              + "alpaca.base-url="
              + baseUrl
              + ". Point it at the paper host.");
    }
    // The fill-listener WS URL is a SEPARATE knob from the REST base-url (the base-url checks above
    // only cover the order path). A -live impl whose WS URL still points at the paper trade-updates
    // stream would authenticate live keys against the paper endpoint (rejected) and silently lose
    // real-time fills. Enforce the live direction only; a paper/stub build may keep the default
    // paper ws-url, so don't break it.
    if (brokerImpl.endsWith("-live") && wsUrl != null && wsUrl.contains("paper")) {
      throw new IllegalStateException(
          "broker.impl="
              + brokerImpl
              + " (live) must not target the paper fill-listener stream; "
              + "exec.fill-listener.ws-url="
              + wsUrl
              + ". Point it at the live trade-updates stream.");
    }
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
