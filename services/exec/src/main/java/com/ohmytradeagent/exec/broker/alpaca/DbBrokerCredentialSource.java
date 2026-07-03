package com.ohmytradeagent.exec.broker.alpaca;

import static com.ohmytradeagent.exec.broker.BrokerCredentialSource.unavailable;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import com.ohmytradeagent.exec.broker.BrokerClientRegistry;
import com.ohmytradeagent.exec.broker.BrokerCredentialSource;
import com.ohmytradeagent.exec.broker.BrokerCredentials;
import com.ohmytradeagent.exec.broker.crypto.BrokerCredentialCrypto;
import com.ohmytradeagent.exec.broker.crypto.BrokerCredentialCryptoException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import org.jooq.DSLContext;
import org.jooq.Field;
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
 * <p><b>Refuses live unless explicitly enabled.</b> On a {@code -live} pod ({@code broker.impl}
 * ends {@code -live}) {@link #resolve} throws UNLESS an operator opts in with {@code
 * broker.creds.db.live-enabled=true} (default false). With the flag unset a {@code -live} pod
 * refuses byte-identically to P6-a — DB-sourced creds cannot serve real money. When the flag is
 * enabled, a live credential is served only if the row declares a non-blank {@code
 * expected_account_id}; a blank fails closed (mirrors {@link FileMountedBrokerCredentialSource}'s
 * live seal) so a live credential is never served without a bound account id.
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

  // Column references declared once and reused by both the projection and the row read, so the two
  // can't drift in name or type.
  private static final Field<byte[]> CIPHERTEXT = field("ciphertext", byte[].class);
  private static final Field<byte[]> IV = field("iv", byte[].class);
  private static final Field<byte[]> WRAPPED_DEK = field("wrapped_dek", byte[].class);
  private static final Field<byte[]> DEK_IV = field("dek_iv", byte[].class);
  private static final Field<Integer> KEK_VERSION = field("kek_version", Integer.class);
  private static final Field<String> BASE_URL = field("base_url", String.class);
  private static final Field<String> WS_URL = field("ws_url", String.class);
  private static final Field<String> EXPECTED_ACCOUNT_ID =
      field("expected_account_id", String.class);
  private static final Field<Long> VERSION = field("version", Long.class);
  private static final Field<String> TENANT_ID = field("tenant_id", String.class);
  private static final Field<String> PROVIDER = field("provider", String.class);

  private final DSLContext dsl;
  private final BrokerCredentialCrypto crypto;
  private final boolean live;
  private final boolean liveEnabled;
  private final String accountLevelTenant;

  public DbBrokerCredentialSource(
      DSLContext dsl,
      BrokerCredentialCrypto crypto,
      @Value("${broker.impl:}") String brokerImpl,
      @Value("${broker.creds.account-level-tenant:${EXEC_BOOTSTRAP_TENANT_ID:}}")
          String accountLevelTenant,
      @Value("${broker.creds.db.live-enabled:false}") boolean liveEnabled) {
    this.dsl = dsl;
    this.live = brokerImpl != null && brokerImpl.endsWith("-live");
    // Default-off gate: a -live pod serves DB creds ONLY when an operator explicitly opts in. Unset
    // → preserve the P6-a refusal exactly.
    this.liveEnabled = liveEnabled;
    // The crypto bean (KEK loaded + validated once at boot by BrokerCredentialCryptoConfig —
    // MUST-FIX-4) is shared with the P6-b write path so read + write use the IDENTICAL envelope.
    this.crypto = crypto;
    // Account-level ops (AccountSnapshot, reconciliation) resolve with the ACCOUNT_LEVEL sentinel,
    // which is not a real tenant; map it to the configured tenant whose row holds the account-level
    // credential — mirrors FileMountedBrokerCredentialSource so file→db migration is
    // behavior-equal.
    this.accountLevelTenant = accountLevelTenant;
  }

  @Override
  public BrokerCredentials resolve(String tenantId, String provider) {
    // A -live pod refuses DB creds outright UNLESS an operator opted in via
    // broker.creds.db.live-enabled. Flag unset → byte-identical P6-a refusal (no row is read).
    if (live && !liveEnabled) {
      throw unavailable(
          "db-sourced broker credentials are refused on a -live pod in P6-a (tenant="
              + tenantId
              + " provider="
              + provider
              + ")");
    }

    // ACCOUNT_LEVEL → the configured account-level tenant (whose row holds the credential); a real
    // tenant passes through unchanged. The mapped tenant is then used for the lookup AND the AAD,
    // so
    // the envelope binds to the same tenant the write path used.
    String tenant = resolveTenant(tenantId);

    // One consistent snapshot: a single-row SELECT, all envelope fields read together.
    Record row =
        dsl.select(
                CIPHERTEXT,
                IV,
                WRAPPED_DEK,
                DEK_IV,
                KEK_VERSION,
                BASE_URL,
                WS_URL,
                EXPECTED_ACCOUNT_ID)
            .from(table(TABLE))
            .where(TENANT_ID.eq(tenant))
            .and(PROVIDER.eq(provider))
            .fetchOne();
    if (row == null) {
      // A missing row is a deployment/config error, not transient — fail closed, non-retryable.
      throw unavailable("no credential row for tenant=" + tenant + " provider=" + provider);
    }

    String baseUrl = row.get(BASE_URL);
    String wsUrl = row.get(WS_URL);
    String expected = row.get(EXPECTED_ACCOUNT_ID) == null ? "" : row.get(EXPECTED_ACCOUNT_ID);

    // Live seal (mirrors FileMountedBrokerCredentialSource): on a -live pod a blank
    // expected_account_id would silently disable the P2 account-identity assertion
    // (BrokerAccountIdentityVerifier.verify() no-ops on blank), so a live tenant MUST declare the
    // account its keys authenticate. Checked here — after resolveTenant() and the row read — so it
    // covers the ACCOUNT_LEVEL sentinel path too, and BEFORE any credential is returned. Uses
    // isBlank() (NOT isEmpty()) to match the verifier's no-op predicate EXACTLY — a whitespace-only
    // value must not slip the seal and then skip verification.
    if (live && expected.isBlank()) {
      throw unavailable(
          "missing/blank required 'expected_account_id' for tenant="
              + tenant
              + " provider="
              + provider
              + " — a -live target must declare its expected account");
    }

    int rowKekVersion = row.get(KEK_VERSION);

    byte[] aad = BrokerCredentialCrypto.aad(tenant, provider, expected, rowKekVersion);
    BrokerCredentialCrypto.Envelope env =
        new BrokerCredentialCrypto.Envelope(
            row.get(CIPHERTEXT), row.get(IV), row.get(WRAPPED_DEK), row.get(DEK_IV), rowKekVersion);

    byte[][] fields;
    try {
      byte[] plaintext = crypto.decrypt(env, aad);
      fields = BrokerCredentialCrypto.unpack(plaintext);
    } catch (BrokerCredentialCryptoException e) {
      // MUST-FIX-2/3/7: any auth/AAD/corruption/version failure fails closed; the crypto exception
      // carries only a constant non-secret reason, but we re-wrap to a non-retryable Temporal
      // failure WITHOUT the cause so no provider/buffer detail can ride along. Never the secret.
      throw unavailable(
          "broker credential decryption failed for tenant=" + tenant + " provider=" + provider);
    }

    String apiKeyId = new String(fields[0], StandardCharsets.UTF_8);
    String apiSecretKey = new String(fields[1], StandardCharsets.UTF_8);
    return new BrokerCredentials(
        apiKeyId, apiSecretKey, baseUrl, wsUrl == null ? "" : wsUrl, expected);
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
        dsl.select(VERSION)
            .from(table(TABLE))
            .where(TENANT_ID.eq(resolveTenant(tenantId)))
            .and(PROVIDER.eq(provider))
            .fetchOne(VERSION);
    return version == null ? "absent" : Long.toString(version);
  }

  /**
   * Roster of tenants with a credential row for the provider: {@code SELECT DISTINCT tenant_id FROM
   * broker_credentials WHERE provider = ?}. Per-pod-broker-target-scoped by construction (the pod's
   * exec DB holds only that broker's rows), so no {@code broker_target} filter is needed.
   */
  @Override
  public Set<String> liveTenants(String provider) {
    return new LinkedHashSet<>(
        dsl.selectDistinct(TENANT_ID)
            .from(table(TABLE))
            .where(PROVIDER.eq(provider))
            .fetch(TENANT_ID));
  }

  /**
   * Maps the {@link BrokerClientRegistry#ACCOUNT_LEVEL} sentinel to the configured account-level
   * tenant; passes any real tenant through unchanged. An {@code ACCOUNT_LEVEL} request with no
   * configured {@code broker.creds.account-level-tenant} fails closed rather than guessing a
   * tenant. Mirrors {@link FileMountedBrokerCredentialSource#resolveTenant} so the two sources
   * agree.
   */
  private String resolveTenant(String tenantId) {
    if (!BrokerClientRegistry.ACCOUNT_LEVEL.equals(tenantId)) {
      return tenantId;
    }
    if (accountLevelTenant == null || accountLevelTenant.isBlank()) {
      throw unavailable(
          "account-level credential resolution requested but broker.creds.account-level-tenant is"
              + " unset — refusing to guess a tenant");
    }
    return accountLevelTenant;
  }
}
