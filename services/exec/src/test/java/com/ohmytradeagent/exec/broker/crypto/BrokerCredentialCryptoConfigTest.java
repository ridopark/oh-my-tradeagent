package com.ohmytradeagent.exec.broker.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the hoisted KEK-loading (moved out of {@code DbBrokerCredentialSource}'s constructor into
 * the shared crypto {@link BrokerCredentialCryptoConfig} bean). MUST-FIX-4: a
 * missing/blank/malformed KEK file fails closed at startup so a misconfigured pod crashloops rather
 * than limping along. No Spring context / no DB needed — the bean factory method is exercised
 * directly.
 */
class BrokerCredentialCryptoConfigTest {

  private static final int KEK_VERSION = 1;

  private final BrokerCredentialCryptoConfig config = new BrokerCredentialCryptoConfig();

  @TempDir Path kekDir;

  private static byte[] kek() {
    byte[] k = new byte[32];
    java.util.Arrays.fill(k, (byte) 0x42);
    return k;
  }

  @Test
  void validKekFileBuildsCryptoThatRoundTrips() throws IOException {
    Path kekPath = kekDir.resolve("kek.b64");
    Files.writeString(kekPath, Base64.getEncoder().encodeToString(kek()));

    BrokerCredentialCrypto crypto = config.brokerCredentialCrypto(kekPath.toString(), KEK_VERSION);

    byte[] aad = BrokerCredentialCrypto.aad("dev", "alpaca", "acct-1", KEK_VERSION);
    byte[] plaintext = BrokerCredentialCrypto.pack("k".getBytes(), "s".getBytes());
    BrokerCredentialCrypto.Envelope env = crypto.encrypt(plaintext, aad);
    assertThat(crypto.decrypt(env, aad)).isEqualTo(plaintext);
  }

  @Test
  void missingKekFileFailsClosedAtStartup() {
    assertThatThrownBy(
            () ->
                config.brokerCredentialCrypto(
                    kekDir.resolve("does-not-exist").toString(), KEK_VERSION))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void blankKekFileFailsClosedAtStartup() throws IOException {
    Path blank = kekDir.resolve("blank.b64");
    Files.writeString(blank, "   ");
    assertThatThrownBy(() -> config.brokerCredentialCrypto(blank.toString(), KEK_VERSION))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void malformedBase64KekFailsClosedAtStartup() throws IOException {
    Path bad = kekDir.resolve("bad.b64");
    Files.writeString(bad, "not-valid-base64!!!");
    assertThatThrownBy(() -> config.brokerCredentialCrypto(bad.toString(), KEK_VERSION))
        .isInstanceOf(IllegalStateException.class);
  }
}
