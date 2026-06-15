package com.ohmytradeagent.exec.broker.crypto;

/**
 * A fail-closed envelope-crypto failure: a GCM authentication failure, an AAD mismatch, a corrupt
 * blob, an unknown KEK version, or a malformed packed plaintext. Carries ONLY a constant,
 * non-secret reason string — never key material, ciphertext, KEK bytes, or decrypted plaintext
 * (MUST-FIX-7). The {@link Throwable} cause (a {@code javax.crypto} exception) is intentionally NOT
 * chained, since JCA provider messages can echo buffer contents; only the constant reason
 * propagates.
 */
public class BrokerCredentialCryptoException extends RuntimeException {

  public BrokerCredentialCryptoException(String message) {
    super(message);
  }
}
