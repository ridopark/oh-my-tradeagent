package com.ohmytradeagent.exec.broker.alpaca;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import com.ohmytradeagent.exec.broker.BrokerCredentialSource;
import com.ohmytradeagent.exec.broker.BrokerCredentials;
import com.ohmytradeagent.exec.broker.crypto.BrokerCredentialCrypto;
import com.ohmytradeagent.exec.broker.crypto.BrokerCredentialCryptoException;
import io.temporal.failure.ApplicationFailure;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * P6-a per-tenant credential source: reads each {@code (tenant, provider)} key's credentials from
 * an envelope-encrypted row in the per-broker exec DB's {@code broker_credentials} table and
 * decrypts them in-app at resolve time.
 *
 * <p><b>Dark by default.</b> Selected only by {@code broker.creds.source=db} (mutually exclusive
 * with {@link EnvFallbackBrokerCredentialSource}'s {@code env} default and {@link
 * FileMountedBrokerCredentialSource}'s {@code file}), so the homelab pods — which leave the
 * selector at {@code env} — never construct this bean and resolve credentials byte-identically to
 * P4-a. No cluster sets {@code source=db} in P6-a.
 *
 * <p><b>Refuses live by construction (MUST-FIX-1).</b> On a {@code -live} pod ({@code broker.impl}
 * ends {@code -live}) {@link #resolve} throws UNCONDITIONALLY — DB-sourced creds cannot serve real
 * money in P6-a, a later hardening gate lifts this. This is stronger than the file source's
 * blank-expected seal: it refuses live outright, even for a fully valid row.
 *
 * <p><b>Fail-closed (MUST-FIX-2/3/4/7).</b> A missing row, any AES-GCM authentication failure, an
 * AAD mismatch (the AAD binds the ciphertext to {@code tenant || provider || expected_account_id ||
 * kek_version} so a cross-tenant blob swap fails verification), a corrupt blob, an unknown {@code
 * kek_version}, or a malformed packed plaintext all THROW {@code BrokerCredentialsUnavailable}
 * (non-retryable) out of {@link #resolve}. There is NO catch-and-default path — the source never
 * falls back to env creds and never serves a partial credential. A missing/blank KEK file fails
 * closed at CONSTRUCTION so a misconfigured pod crashloops loudly. Secret material — KEK bytes, the
 * DEK, ciphertext, or decrypted plaintext — never appears in a log line, an exception message, or a
 * {@code toString}.
 */
@Component
@ConditionalOnExpression("'${broker.impl:}'.startsWith('alpaca-')")
@ConditionalOnProperty(name = "broker.creds.source", havingValue = "db")
public class DbBrokerCredentialSource implements BrokerCredentialSource {

  private static final String TABLE = "broker_credentials";

  private final DSLContext dsl;
  private final BrokerCredentialCrypto crypto;
  private final boolean live;

  public DbBrokerCredentialSource(
      DSLContext dsl,
      @Value("${broker.creds.db.kek-path:/etc/broker-kek/kek}") String kekPath,
      @Value("${broker.creds.db.kek-version:1}") int kekVersion,
      @Value("${broker.impl:}") String brokerImpl) {
    this.dsl = dsl;
    this.live = brokerImpl != null && brokerImpl.endsWith("-live");
    // MUST-FIX-4: load + validate the KEK at construction so a misconfigured pod fails loudly at
    // boot instead of limping along and failing every resolve. KISS single active version for
    // P6-a, but the crypto util keys the map by version so the row's kek_version column is honored.
    byte[] kek = loadKek(kekPath);
    this.crypto = new BrokerCredentialCrypto(Map.of(kekVersion, kek), kekVersion);
  }

  @Override
  public BrokerCredentials resolve(String tenantId, String provider) {
    // MUST-FIX-1: a -live pod refuses DB creds outright in P6-a — no row, however valid, is served.
    if (live) {
      throw unavailable(
          "db-sourced broker credentials are refused on a -live pod in P6-a (tenant="
              + tenantId
              + " provider="
              + provider
              + ")");
    }

    // One consistent snapshot: a single-row SELECT, all envelope fields read together.
    Record row =
        dsl.select(
                field("ciphertext", byte[].class),
                field("iv", byte[].class),
                field("wrapped_dek", byte[].class),
                field("dek_iv", byte[].class),
                field("kek_version", Integer.class),
                field("base_url", String.class),
                field("ws_url", String.class),
                field("expected_account_id", String.class))
            .from(table(TABLE))
            .where(field("tenant_id").eq(tenantId))
            .and(field("provider").eq(provider))
            .fetchOne();
    if (row == null) {
      // A missing row is a deployment/config error, not transient — fail closed, non-retryable.
      throw unavailable("no credential row for tenant=" + tenantId + " provider=" + provider);
    }

    String baseUrl = row.get(field("base_url", String.class));
    String wsUrl = row.get(field("ws_url", String.class));
    String expectedAccountId = row.get(field("expected_account_id", String.class));
    int rowKekVersion = row.get(field("kek_version", Integer.class));

    String expectedForAad = expectedAccountId == null ? "" : expectedAccountId;
    byte[] aad = aad(tenantId, provider, expectedForAad, rowKekVersion);
    BrokerCredentialCrypto.Envelope env =
        new BrokerCredentialCrypto.Envelope(
            row.get(field("ciphertext", byte[].class)),
            row.get(field("iv", byte[].class)),
            row.get(field("wrapped_dek", byte[].class)),
            row.get(field("dek_iv", byte[].class)),
            rowKekVersion);

    byte[][] fields;
    try {
      byte[] plaintext = crypto.decrypt(env, aad);
      fields = BrokerCredentialCrypto.unpack(plaintext);
    } catch (BrokerCredentialCryptoException e) {
      // MUST-FIX-2/3/7: any auth/AAD/corruption/version failure fails closed; the crypto exception
      // carries only a constant non-secret reason, but we re-wrap to a non-retryable Temporal
      // failure WITHOUT the cause so no provider/buffer detail can ride along. Never the secret.
      throw unavailable(
          "broker credential decryption failed for tenant=" + tenantId + " provider=" + provider);
    }

    String apiKeyId = new String(fields[0], StandardCharsets.UTF_8);
    String apiSecretKey = new String(fields[1], StandardCharsets.UTF_8);
    return new BrokerCredentials(
        apiKeyId,
        apiSecretKey,
        baseUrl,
        wsUrl == null ? "" : wsUrl,
        expectedAccountId == null ? "" : expectedAccountId);
  }

  /**
   * Change-token for the key's row: the row {@code version}, bumped on every UPDATE
   * (write/rotation), so the registry rebuilds the client — re-running the fail-closed
   * mode-coherence + account identity assertion — when an operator rotates or re-saves the
   * credential (MUST-FIX-8). A missing row returns the {@code "absent"} sentinel so the registry
   * rebuilds and {@link #resolve} then throws the fail-closed no-row error. Reads no secret bytes.
   */
  @Override
  public String fingerprint(String tenantId, String provider) {
    Long version =
        dsl.select(field("version", Long.class))
            .from(table(TABLE))
            .where(field("tenant_id").eq(tenantId))
            .and(field("provider").eq(provider))
            .fetchOne(field("version", Long.class));
    return version == null ? "absent" : Long.toString(version);
  }

  private static byte[] aad(
      String tenant, String provider, String expectedAccount, int kekVersion) {
    return (tenant + "|" + provider + "|" + expectedAccount + "|" + kekVersion)
        .getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Reads the base64-encoded 32-byte KEK from {@code kekPath} and validates it. A missing/blank
   * file or malformed base64 throws {@link IllegalStateException} at construction (MUST-FIX-4) so
   * the pod crashloops; the bytes never appear in the message.
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

  private static ApplicationFailure unavailable(String message) {
    return ApplicationFailure.newNonRetryableFailure(message, "BrokerCredentialsUnavailable");
  }
}
