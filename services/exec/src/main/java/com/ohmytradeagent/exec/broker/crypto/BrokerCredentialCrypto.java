package com.ohmytradeagent.exec.broker.crypto;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pure, dependency-free envelope-crypto for per-tenant broker credentials (P6-a). No Spring, no DB,
 * no custom crypto — only JCA primitives, so it is unit-testable in isolation.
 *
 * <p><b>Envelope scheme.</b> Each write generates a fresh 256-bit AES DEK and two 12-byte {@link
 * SecureRandom} nonces. The plaintext is AES-256-GCM-encrypted under the DEK (with the caller's AAD
 * bound in), then the DEK itself is AES-256-GCM-wrapped under the process-wide KEK (with the same
 * AAD bound in). The result — {@code {ciphertext, iv, wrappedDek, dekIv, kekVersion}} — is what the
 * DB row persists. A KEK rotation re-wraps only the DEK, so {@code kek_version} on the row selects
 * which KEK unwraps it; an unknown version is a fail-closed throw.
 *
 * <p><b>Fail-closed.</b> Any GCM authentication failure, AAD mismatch, corrupt blob, unknown KEK
 * version, or malformed packed plaintext throws {@link BrokerCredentialCryptoException} with a
 * constant, non-secret reason — never key material, ciphertext, KEK bytes, or decrypted plaintext
 * (MUST-FIX-7). There is no catch-and-default path.
 */
public final class BrokerCredentialCrypto {

  private static final String TRANSFORM = "AES/GCM/NoPadding";
  private static final String KEY_ALG = "AES";
  private static final int GCM_TAG_BITS = 128;
  private static final int NONCE_BYTES = 12;
  private static final int DEK_BITS = 256;
  private static final int KEK_BYTES = 32;

  /** The persisted envelope. {@code byte[]} fields are defensively copied in/out (no aliasing). */
  public record Envelope(
      byte[] ciphertext, byte[] iv, byte[] wrappedDek, byte[] dekIv, int kekVersion) {

    public Envelope {
      ciphertext = ciphertext.clone();
      iv = iv.clone();
      wrappedDek = wrappedDek.clone();
      dekIv = dekIv.clone();
    }

    @Override
    public byte[] ciphertext() {
      return ciphertext.clone();
    }

    @Override
    public byte[] iv() {
      return iv.clone();
    }

    @Override
    public byte[] wrappedDek() {
      return wrappedDek.clone();
    }

    @Override
    public byte[] dekIv() {
      return dekIv.clone();
    }
  }

  private final Map<Integer, SecretKey> keks;
  private final int activeVersion;
  private final SecureRandom random = new SecureRandom();

  /**
   * @param keksByVersion KEK bytes keyed by version; each value must be a 32-byte (AES-256) key.
   * @param activeVersion the version used for {@link #encrypt} (must be present in the map).
   */
  public BrokerCredentialCrypto(Map<Integer, byte[]> keksByVersion, int activeVersion) {
    if (keksByVersion == null || keksByVersion.isEmpty()) {
      throw new IllegalArgumentException("no KEK supplied");
    }
    Map<Integer, SecretKey> built = new LinkedHashMap<>();
    for (Map.Entry<Integer, byte[]> e : keksByVersion.entrySet()) {
      byte[] kek = e.getValue();
      if (kek == null || kek.length != KEK_BYTES) {
        // Length only — never the bytes.
        throw new IllegalArgumentException(
            "KEK version " + e.getKey() + " must be 32 bytes (AES-256)");
      }
      built.put(e.getKey(), new SecretKeySpec(kek.clone(), KEY_ALG));
    }
    if (!built.containsKey(activeVersion)) {
      throw new IllegalArgumentException("active KEK version " + activeVersion + " not in key map");
    }
    this.keks = Map.copyOf(built);
    this.activeVersion = activeVersion;
  }

  /**
   * Envelope-encrypts {@code plaintext} under a fresh DEK, binding {@code aad} into both layers.
   */
  public Envelope encrypt(byte[] plaintext, byte[] aad) {
    try {
      SecretKey dek = newDek();
      byte[] iv = newNonce();
      byte[] ciphertext = gcm(Cipher.ENCRYPT_MODE, dek, iv, aad, plaintext);

      byte[] dekIv = newNonce();
      byte[] wrappedDek =
          gcm(Cipher.ENCRYPT_MODE, keks.get(activeVersion), dekIv, aad, dek.getEncoded());

      return new Envelope(ciphertext, iv, wrappedDek, dekIv, activeVersion);
    } catch (GeneralSecurityException e) {
      // Constant reason only — never the cause (provider messages can echo buffer contents).
      throw new BrokerCredentialCryptoException("broker credential encryption failed");
    }
  }

  /**
   * Unwraps the DEK with the row's {@code kekVersion} KEK, then decrypts. Fail-closed throughout.
   */
  public byte[] decrypt(Envelope env, byte[] aad) {
    SecretKey kek = keks.get(env.kekVersion());
    if (kek == null) {
      throw new BrokerCredentialCryptoException(
          "unknown KEK version " + env.kekVersion() + " — refusing to decrypt");
    }
    try {
      byte[] dekBytes = gcm(Cipher.DECRYPT_MODE, kek, env.dekIv(), aad, env.wrappedDek());
      SecretKey dek = new SecretKeySpec(dekBytes, KEY_ALG);
      return gcm(Cipher.DECRYPT_MODE, dek, env.iv(), aad, env.ciphertext());
    } catch (GeneralSecurityException e) {
      // GCM auth failure / AAD mismatch / corrupt blob — fail closed, constant reason, no cause.
      throw new BrokerCredentialCryptoException("broker credential decryption failed");
    }
  }

  private SecretKey newDek() throws GeneralSecurityException {
    KeyGenerator kg = KeyGenerator.getInstance(KEY_ALG);
    kg.init(DEK_BITS, random);
    return kg.generateKey();
  }

  private byte[] newNonce() {
    byte[] nonce = new byte[NONCE_BYTES];
    random.nextBytes(nonce);
    return nonce;
  }

  private static byte[] gcm(int mode, SecretKey key, byte[] nonce, byte[] aad, byte[] input)
      throws GeneralSecurityException {
    Cipher cipher = Cipher.getInstance(TRANSFORM);
    cipher.init(mode, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
    if (aad != null) {
      cipher.updateAAD(aad);
    }
    return cipher.doFinal(input);
  }

  /**
   * Length-prefixes the two field byte arrays into one blob: {@code [int len(a)][a][int
   * len(b)][b]}. Big-endian, 4-byte lengths. {@link #unpack} is the exact inverse.
   */
  public static byte[] pack(byte[] a, byte[] b) {
    ByteBuffer buf = ByteBuffer.allocate(4 + a.length + 4 + b.length);
    buf.putInt(a.length).put(a).putInt(b.length).put(b);
    return buf.array();
  }

  /** Inverse of {@link #pack}; a malformed/truncated blob is a fail-closed throw. */
  public static byte[][] unpack(byte[] packed) {
    try {
      ByteBuffer buf = ByteBuffer.wrap(packed);
      byte[] a = new byte[buf.getInt()];
      buf.get(a);
      byte[] b = new byte[buf.getInt()];
      buf.get(b);
      if (buf.hasRemaining()) {
        throw new BrokerCredentialCryptoException(
            "packed broker credential blob has trailing bytes");
      }
      return new byte[][] {a, b};
    } catch (RuntimeException e) {
      if (e instanceof BrokerCredentialCryptoException bcce) {
        throw bcce;
      }
      throw new BrokerCredentialCryptoException("malformed packed broker credential blob");
    }
  }
}
