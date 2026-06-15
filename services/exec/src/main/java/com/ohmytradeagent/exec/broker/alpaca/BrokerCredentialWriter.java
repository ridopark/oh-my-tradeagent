package com.ohmytradeagent.exec.broker.alpaca;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import com.ohmytradeagent.exec.broker.crypto.BrokerCredentialCrypto;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * P6-b server-side credential WRITE path. INERT: tests are the sole caller in P6-b — there is no
 * HTTP endpoint, no Temporal Activity, no contract DTO (those are P6-c). A tenant-entered broker
 * key is validated against the broker's {@code /v2/account} identity probe BEFORE it is envelope-
 * encrypted and persisted into the {@code broker_credentials} table, so a bad key never lands.
 *
 * <p><b>Gated identically to {@link DbBrokerCredentialSource}</b> — {@code broker.creds.source=db}
 * AND an {@code alpaca-*} impl — so the homelab pods (selector at {@code env}) never construct this
 * bean; the write path simply does not exist there.
 *
 * <p><b>Refuses live by construction (MUST-FIX-1, mirrors the read source).</b> On a {@code -live}
 * pod the writer throws unconditionally — DB-sourced creds stay paper-only until a later hardening
 * gate, subsuming the blank-expected-for-live rejection.
 *
 * <p><b>Validate-on-entry (MUST-FIX-6), BEFORE any DB write.</b> A throwaway broker is built from
 * the UNSAVED keys via the exact registry sequence ({@code assertCredentialsPresent} → {@code
 * assertCoherent} → {@code AlpacaConfig.buildRestClient} → {@code new AlpacaPaperBroker} → {@code
 * BrokerAccountIdentityVerifier.verify}) and rejected on (a) an account mismatch (the keys
 * authenticate a different account than declared) or (b) a paper/live host mismatch. A rejecting
 * save throws and persists NOTHING.
 *
 * <p><b>Always re-encrypt + CAS version bump.</b> Every write generates a fresh DEK + nonces (the
 * writer is the only writer and offers no partial-field update, so the read-path AAD invariant
 * cannot be broken). Persistence is a CAS UPSERT: an INSERT inserts {@code version=1}; on conflict
 * the {@code DO UPDATE} bumps {@code version} only when the stored version equals {@code
 * expectedVersion}, else it affects zero rows and the writer throws {@link
 * OptimisticLockException}.
 *
 * <p><b>No key material (MUST-FIX-7).</b> The api-key/secret, KEK, DEK, and plaintext never appear
 * in a log line or an exception message.
 */
@Component
@ConditionalOnExpression("'${broker.impl:}'.startsWith('alpaca-')")
@ConditionalOnProperty(name = "broker.creds.source", havingValue = "db")
public class BrokerCredentialWriter {

  private static final Logger log = LoggerFactory.getLogger(BrokerCredentialWriter.class);

  private final DSLContext dsl;
  private final BrokerCredentialCrypto crypto;
  private final RestClient.Builder restClientBuilder;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;
  private final boolean live;
  private final String brokerImpl;

  public BrokerCredentialWriter(
      DSLContext dsl,
      BrokerCredentialCrypto crypto,
      RestClient.Builder restClientBuilder,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry,
      @Value("${broker.impl:}") String brokerImpl) {
    this.dsl = dsl;
    this.crypto = crypto;
    this.restClientBuilder = restClientBuilder;
    this.objectMapper = objectMapper;
    this.meterRegistry = meterRegistry;
    this.live = brokerImpl != null && brokerImpl.endsWith("-live");
    this.brokerImpl = brokerImpl == null ? "" : brokerImpl;
  }

  /**
   * Result of a successful credential write. All three fields are NON-SECRET metadata the UI-P2-a
   * audit needs to record a complete {@code SAVED} event: the CAS-bumped row {@code version}, the
   * {@code kekVersion} the row's DEK was wrapped under, and the verified {@code brokerAccountId}
   * (the {@code /v2/account} number, or the declared account, or {@code null}/blank when the probe
   * was disabled by a blank declared account). NONE of these is key material.
   */
  public record SaveResult(long version, int kekVersion, String brokerAccountId) {}

  /**
   * Validates the supplied broker keys against {@code /v2/account} and, on success, persists them
   * envelope-encrypted into {@code broker_credentials} via a CAS UPSERT, returning the row's new
   * {@code version} plus the non-secret {@code kekVersion} and verified {@code brokerAccountId}.
   *
   * @param tenantId tenant the keys belong to
   * @param provider broker provider (e.g. {@code "alpaca"})
   * @param apiKeyId tenant-entered broker API key id (never logged)
   * @param apiSecretKey tenant-entered broker API secret (never logged)
   * @param baseUrl REST base URL the keys authenticate against
   * @param wsUrl fill-listener WS URL (carried for mode coherence)
   * @param declaredAccountId the brokerage account the operator says these keys belong to; the keys
   *     MUST authenticate this account (blank disables the probe, paper/back-compat)
   * @param expectedVersion the stored row version the caller read (0 for a first write)
   * @param actor audit subject recorded in {@code updated_by}
   * @return the {@link SaveResult} (new row {@code version}, active {@code kekVersion}, verified
   *     {@code brokerAccountId})
   * @throws IllegalStateException on a {@code -live} pod (refuse-by-construction), missing creds, a
   *     paper/live host mismatch, or an account mismatch — in every case NO row is written
   * @throws OptimisticLockException if {@code expectedVersion} does not match the stored version
   */
  public SaveResult save(
      String tenantId,
      String provider,
      String apiKeyId,
      String apiSecretKey,
      String baseUrl,
      String wsUrl,
      String declaredAccountId,
      long expectedVersion,
      String actor) {
    // MUST-FIX-1: a -live pod refuses to persist DB creds outright in P6-b. Checked first so no
    // network probe or DB write can happen on a live pod.
    if (live) {
      throw new IllegalStateException(
          "db-sourced broker credential writes are refused on a -live pod in P6-b (tenant="
              + tenantId
              + " provider="
              + provider
              + ")");
    }

    // MUST-FIX-6: validate-on-entry BEFORE any DB write. A throw here means the keys are bad
    // (missing / paper-live mismatch / wrong account) → reject, persist nothing. On success it
    // returns the verified /v2/account number (null when the probe is disabled by a blank declared
    // account) — a NON-SECRET identifier the SAVED audit reports.
    String verifiedAccount =
        validateOnEntry(tenantId, apiKeyId, apiSecretKey, baseUrl, wsUrl, declaredAccountId);

    // Always re-encrypt fresh: new DEK + nonces, AAD bound to the row identity (shared formatter so
    // the read path decrypts byte-identically). The writer is the only writer and replaces all
    // fields, so the read-path AAD invariant can never be torn by a partial update. The AAD's
    // kek_version is derived from the crypto bean's active version (== env.kekVersion() == the
    // stored column == the read-AAD), so the round-trip holds for ANY active version (KEK
    // rotation).
    String accountForAad = declaredAccountId == null ? "" : declaredAccountId;
    byte[] aad =
        BrokerCredentialCrypto.aad(tenantId, provider, accountForAad, crypto.activeVersion());
    byte[] plaintext =
        BrokerCredentialCrypto.pack(
            apiKeyId.getBytes(StandardCharsets.UTF_8),
            apiSecretKey.getBytes(StandardCharsets.UTF_8));
    BrokerCredentialCrypto.Envelope env = crypto.encrypt(plaintext, aad);

    long newVersion =
        upsert(tenantId, provider, env, baseUrl, wsUrl, declaredAccountId, expectedVersion, actor);

    log.info(
        "broker credential write committed tenant={} provider={} version={} actor={}",
        tenantId,
        provider,
        newVersion,
        actor);
    // brokerAccountId: the probe-verified account when the probe ran, else the operator-declared
    // account (so a blank-probe paper write still reports what it claimed). Both are non-secret.
    String brokerAccountId =
        verifiedAccount != null
            ? verifiedAccount
            : (declaredAccountId == null ? "" : declaredAccountId);
    return new SaveResult(newVersion, (int) crypto.activeVersion(), brokerAccountId);
  }

  /**
   * Builds a THROWAWAY broker from the UNSAVED keys and runs the identical fail-closed sequence the
   * registry uses on its hot path, so a key that authenticates the wrong account — or a paper/live
   * host mismatch — is rejected before any persistence. Reuses {@link AlpacaModeCoherence}, {@link
   * AlpacaConfig#buildRestClient}, {@link AlpacaPaperBroker}, and {@link
   * BrokerAccountIdentityVerifier} so write-time validation matches read-time enforcement exactly.
   */
  private String validateOnEntry(
      String tenantId,
      String apiKeyId,
      String apiSecretKey,
      String baseUrl,
      String wsUrl,
      String declaredAccountId) {
    AlpacaModeCoherence.assertCredentialsPresent(apiKeyId, apiSecretKey);
    AlpacaModeCoherence.assertCoherent(brokerImpl, baseUrl, wsUrl);

    RestClient restClient =
        AlpacaConfig.buildRestClient(restClientBuilder, baseUrl, apiKeyId, apiSecretKey);
    OptionsBroker probe = new AlpacaPaperBroker(restClient, objectMapper, meterRegistry);

    try {
      // Returns the verified /v2/account number, or null when the probe is disabled (blank declared
      // account). A NON-SECRET identifier only.
      return BrokerAccountIdentityVerifier.verify(
          probe, declaredAccountId, "credential save tenant=" + tenantId);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "interrupted verifying broker account identity for credential save tenant=" + tenantId,
          e);
    }
  }

  /**
   * CAS UPSERT. A first write (no conflict) inserts {@code version=1}; on conflict the {@code DO
   * UPDATE} replaces every envelope/endpoint field and bumps {@code version} ONLY when the stored
   * version equals {@code expectedVersion}. Zero affected rows means the CAS lost (a concurrent
   * writer moved the version, OR a blind first-write hit an already-present row) → {@link
   * OptimisticLockException}. Parameterized — no key material reaches the SQL text.
   */
  private long upsert(
      String tenantId,
      String provider,
      BrokerCredentialCrypto.Envelope env,
      String baseUrl,
      String wsUrl,
      String declaredAccountId,
      long expectedVersion,
      String actor) {
    int updated =
        dsl.execute(
            "INSERT INTO broker_credentials ("
                + "tenant_id, provider, ciphertext, iv, wrapped_dek, dek_iv, kek_version, "
                + "base_url, ws_url, expected_account_id, version, updated_at, updated_by) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, now(), ?) "
                + "ON CONFLICT (tenant_id, provider) DO UPDATE SET "
                + "ciphertext = excluded.ciphertext, iv = excluded.iv, "
                + "wrapped_dek = excluded.wrapped_dek, dek_iv = excluded.dek_iv, "
                + "kek_version = excluded.kek_version, base_url = excluded.base_url, "
                + "ws_url = excluded.ws_url, expected_account_id = excluded.expected_account_id, "
                + "version = broker_credentials.version + 1, updated_at = now(), "
                + "updated_by = excluded.updated_by "
                + "WHERE broker_credentials.version = ?",
            tenantId,
            provider,
            env.ciphertext(),
            env.iv(),
            env.wrappedDek(),
            env.dekIv(),
            env.kekVersion(),
            baseUrl,
            wsUrl,
            declaredAccountId,
            actor,
            expectedVersion);

    if (updated == 0) {
      // INSERT path always affects 1; zero rows ⇒ a conflict whose DO UPDATE WHERE-version did not
      // match. Either a concurrent writer moved the version or a blind first-write (expected=0) hit
      // a pre-existing row.
      throw new OptimisticLockException(
          "stale expectedVersion="
              + expectedVersion
              + " for tenant="
              + tenantId
              + " provider="
              + provider
              + " — the stored credential version moved (or a row already exists); re-read and"
              + " retry");
    }
    return expectedVersion + 1;
  }
}
