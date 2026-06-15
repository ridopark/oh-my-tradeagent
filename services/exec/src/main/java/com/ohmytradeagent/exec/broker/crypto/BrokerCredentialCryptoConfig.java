package com.ohmytradeagent.exec.broker.crypto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the single process-wide {@link BrokerCredentialCrypto} bean for the encrypted-DB
 * credential path, loading + validating the KEK ONCE at startup (MUST-FIX-4: a missing/blank/
 * malformed KEK crashloops the pod at boot rather than failing every resolve).
 *
 * <p>Gated identically to {@link com.ohmytradeagent.exec.broker.alpaca.DbBrokerCredentialSource} —
 * {@code broker.creds.source=db} AND an {@code alpaca-*} impl — so the homelab pods (which leave
 * the selector at {@code env}) never construct this bean and stay byte-identical to P4-a. There are
 * now TWO crypto consumers (the read source and the P6-b write path), so the KEK-loading that used
 * to live in the source's constructor is hoisted here and injected into both.
 */
@Configuration
@ConditionalOnExpression("'${broker.impl:}'.startsWith('alpaca-')")
@ConditionalOnProperty(name = "broker.creds.source", havingValue = "db")
public class BrokerCredentialCryptoConfig {

  @Bean
  public BrokerCredentialCrypto brokerCredentialCrypto(
      @Value("${broker.creds.db.kek-path:/etc/broker-kek/kek}") String kekPath,
      @Value("${broker.creds.db.kek-version:1}") int kekVersion) {
    // KISS single active version for P6; the crypto util keys the map by version so a row's
    // kek_version column is still honored on decrypt.
    byte[] kek = loadKek(kekPath);
    return new BrokerCredentialCrypto(Map.of(kekVersion, kek), kekVersion);
  }

  /**
   * Reads the base64-encoded 32-byte KEK from {@code kekPath} and validates it. A missing/blank
   * file or malformed base64 throws {@link IllegalStateException} at startup (MUST-FIX-4) so the
   * pod crashloops; the bytes never appear in the message.
   */
  private static byte[] loadKek(String kekPath) {
    Path path = Path.of(kekPath);
    if (!Files.isRegularFile(path)) {
      throw new IllegalStateException("broker KEK file not found at " + kekPath);
    }
    String b64;
    try {
      b64 = Files.readString(path, StandardCharsets.UTF_8).strip();
    } catch (IOException e) {
      throw new IllegalStateException("failed reading broker KEK file at " + kekPath, e);
    }
    if (b64.isEmpty()) {
      throw new IllegalStateException("broker KEK file is blank at " + kekPath);
    }
    try {
      return Base64.getDecoder().decode(b64);
    } catch (IllegalArgumentException e) {
      // Never include the (decoded or raw) bytes — just the path and the failure kind.
      throw new IllegalStateException("broker KEK file is not valid base64 at " + kekPath);
    }
  }
}
