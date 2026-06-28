package com.ohmytradeagent.exec.broker.alpaca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import com.ohmytradeagent.exec.broker.BrokerClientRegistry;
import com.ohmytradeagent.exec.broker.BrokerCredentials;
import com.ohmytradeagent.exec.broker.crypto.BrokerCredentialCrypto;
import io.temporal.failure.ApplicationFailure;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Testcontainers Postgres + Flyway IT for the DARK encrypted-DB credential source (P6-a). Seeds
 * rows via the same {@link BrokerCredentialCrypto} the (later) write path uses, then exercises the
 * read/decrypt path + every MUST-FIX fail-closed gate. Gated on {@code RUN_DB_ITS=true} (mirrors
 * {@code JooqOrderIntentJournalIT}).
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class DbBrokerCredentialSourceIT {

  private static final String PAPER = "alpaca-paper";
  private static final String LIVE = "alpaca-live";
  private static final String PAPER_HOST = "https://paper-api.alpaca.markets";
  private static final String PROVIDER = "alpaca";
  private static final int KEK_VERSION = 1;

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  private static java.sql.Connection conn;
  private static DSLContext dsl;
  private static byte[] kekBytes;

  @BeforeAll
  static void initDb() throws Exception {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/exec")
        .load()
        .migrate();
    conn =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    dsl = DSL.using(conn, SQLDialect.POSTGRES);

    kekBytes = new byte[32];
    java.util.Arrays.fill(kekBytes, (byte) 0x42);
  }

  @AfterAll
  static void closeDb() throws Exception {
    if (conn != null) conn.close();
  }

  @BeforeEach
  void truncate() {
    dsl.deleteFrom(table("broker_credentials")).execute();
  }

  private static final String ACCT_TENANT = "acct-level-tenant";

  private DbBrokerCredentialSource source(String brokerImpl) {
    return source(brokerImpl, ACCT_TENANT);
  }

  private DbBrokerCredentialSource source(String brokerImpl, String accountLevelTenant) {
    return new DbBrokerCredentialSource(dsl, crypto(), brokerImpl, accountLevelTenant);
  }

  private static byte[] aad(
      String tenant, String provider, String expectedAccount, int kekVersion) {
    return (tenant + "|" + provider + "|" + expectedAccount + "|" + kekVersion)
        .getBytes(StandardCharsets.UTF_8);
  }

  /** Encrypt + insert a row exactly as the (later) write path will, with the given crypto. */
  private void seed(
      BrokerCredentialCrypto crypto,
      String tenant,
      String keyId,
      String secret,
      String baseUrl,
      String wsUrl,
      String expectedAccount,
      long version) {
    String acctForAad = expectedAccount == null ? "" : expectedAccount;
    byte[] plaintext =
        BrokerCredentialCrypto.pack(
            keyId.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8));
    BrokerCredentialCrypto.Envelope env =
        crypto.encrypt(plaintext, aad(tenant, PROVIDER, acctForAad, KEK_VERSION));
    insert(tenant, env, baseUrl, wsUrl, expectedAccount, version);
  }

  private void insert(
      String tenant,
      BrokerCredentialCrypto.Envelope env,
      String baseUrl,
      String wsUrl,
      String expectedAccount,
      long version) {
    dsl.insertInto(table("broker_credentials"))
        .set(field("tenant_id"), tenant)
        .set(field("provider"), PROVIDER)
        .set(field("ciphertext"), env.ciphertext())
        .set(field("iv"), env.iv())
        .set(field("wrapped_dek"), env.wrappedDek())
        .set(field("dek_iv"), env.dekIv())
        .set(field("kek_version"), env.kekVersion())
        .set(field("base_url"), baseUrl)
        .set(field("ws_url"), wsUrl)
        .set(field("expected_account_id"), expectedAccount)
        .set(field("version"), version)
        .set(field("updated_by"), "test")
        .execute();
  }

  private BrokerCredentialCrypto crypto() {
    return new BrokerCredentialCrypto(Map.of(KEK_VERSION, kekBytes), KEK_VERSION);
  }

  @Test
  void resolveReturnsDecryptedCredentials() {
    seed(crypto(), "alice", "alice-key", "alice-secret", PAPER_HOST, "wss://x", "111", 1);

    BrokerCredentials c = source(PAPER).resolve("alice", PROVIDER);

    assertThat(c.apiKeyId()).isEqualTo("alice-key");
    assertThat(c.apiSecretKey()).isEqualTo("alice-secret");
    assertThat(c.baseUrl()).isEqualTo(PAPER_HOST);
    assertThat(c.wsUrl()).isEqualTo("wss://x");
    assertThat(c.expectedAccountId()).isEqualTo("111");
  }

  @Test
  void resolvePerTenantIsolated() {
    seed(crypto(), "alice", "alice-key", "alice-secret", PAPER_HOST, "wss://x", "111", 1);
    seed(crypto(), "bob", "bob-key", "bob-secret", PAPER_HOST, "wss://y", "222", 1);

    assertThat(source(PAPER).resolve("alice", PROVIDER).apiKeyId()).isEqualTo("alice-key");
    assertThat(source(PAPER).resolve("bob", PROVIDER).apiKeyId()).isEqualTo("bob-key");
  }

  @Test
  void accountLevelSentinelResolvesViaConfiguredTenant() {
    // Account-level ops (AccountSnapshot / reconciliation) resolve with the ACCOUNT_LEVEL sentinel;
    // it must map to the configured account-level tenant's row (the row was written + AAD-bound to
    // that real tenant), not be looked up literally.
    seed(crypto(), ACCT_TENANT, "acct-key", "acct-secret", PAPER_HOST, "wss://z", "999", 1);

    BrokerCredentials c = source(PAPER).resolve(BrokerClientRegistry.ACCOUNT_LEVEL, PROVIDER);

    assertThat(c.apiKeyId()).isEqualTo("acct-key");
    assertThat(c.expectedAccountId()).isEqualTo("999");
    assertThat(source(PAPER).fingerprint(BrokerClientRegistry.ACCOUNT_LEVEL, PROVIDER))
        .isEqualTo("1");
  }

  @Test
  void accountLevelSentinelWithBlankConfiguredTenantFailsClosed() {
    assertThatThrownBy(
            () -> source(PAPER, "").resolve(BrokerClientRegistry.ACCOUNT_LEVEL, PROVIDER))
        .isInstanceOf(ApplicationFailure.class)
        .hasMessageContaining("broker.creds.account-level-tenant is");
  }

  @Test
  void nullWsAndExpectedAccountDefaultToBlank() {
    seed(crypto(), "alice", "alice-key", "alice-secret", PAPER_HOST, null, null, 1);

    BrokerCredentials c = source(PAPER).resolve("alice", PROVIDER);
    assertThat(c.wsUrl()).isEmpty();
    assertThat(c.expectedAccountId()).isEmpty();
  }

  @Test
  void absentRowFailsClosedNonRetryable() {
    assertThatThrownBy(() -> source(PAPER).resolve("nobody", PROVIDER))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            af -> {
              assertThat(af.getType()).isEqualTo("BrokerCredentialsUnavailable");
              assertThat(af.isNonRetryable()).isTrue();
            });
  }

  @Test
  void liveImplRefusesByConstruction() {
    // MUST-FIX-1: a -live pod must NOT serve DB creds in P6-a even for a fully valid row.
    seed(
        crypto(),
        "alice",
        "alice-key",
        "alice-secret",
        "https://api.alpaca.markets",
        null,
        "111",
        1);

    assertThatThrownBy(() -> source(LIVE).resolve("alice", PROVIDER))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            af -> assertThat(af.getType()).isEqualTo("BrokerCredentialsUnavailable"));
  }

  @Test
  void corruptCiphertextFailsClosed() {
    // MUST-FIX-2: a flipped ciphertext byte fails GCM auth → throw, serve nothing.
    seed(crypto(), "alice", "alice-key", "alice-secret", PAPER_HOST, null, "111", 1);
    byte[] ct =
        dsl.select(field("ciphertext", byte[].class))
            .from(table("broker_credentials"))
            .where(field("tenant_id").eq("alice"))
            .fetchOne(field("ciphertext", byte[].class));
    ct[0] ^= 0x01;
    dsl.update(table("broker_credentials"))
        .set(field("ciphertext"), ct)
        .where(field("tenant_id").eq("alice"))
        .execute();

    assertThatThrownBy(() -> source(PAPER).resolve("alice", PROVIDER))
        .isInstanceOf(ApplicationFailure.class);
  }

  @Test
  void aadSwappedRowFailsClosed() {
    // MUST-FIX-2/3: a blob valid for tenant A, physically placed in tenant B's row, fails GCM
    // because AAD binds the ciphertext to the tenant. Encrypt under A's AAD, insert under B.
    BrokerCredentialCrypto c = crypto();
    byte[] plaintext =
        BrokerCredentialCrypto.pack(
            "a-key".getBytes(StandardCharsets.UTF_8), "a-secret".getBytes(StandardCharsets.UTF_8));
    BrokerCredentialCrypto.Envelope envForA =
        c.encrypt(plaintext, aad("alice", PROVIDER, "111", KEK_VERSION));
    // Place alice's blob into bob's row (bob's AAD will differ → GCM verify fails on resolve).
    insert("bob", envForA, PAPER_HOST, null, "111", 1);

    assertThatThrownBy(() -> source(PAPER).resolve("bob", PROVIDER))
        .isInstanceOf(ApplicationFailure.class);
  }

  @Test
  void unknownKekVersionRowFailsClosed() {
    // MUST-FIX-2: a row tagged with a KEK version the source has no key for fails closed.
    seed(crypto(), "alice", "alice-key", "alice-secret", PAPER_HOST, null, "111", 1);
    dsl.update(table("broker_credentials"))
        .set(field("kek_version"), 99)
        .where(field("tenant_id").eq("alice"))
        .execute();

    assertThatThrownBy(() -> source(PAPER).resolve("alice", PROVIDER))
        .isInstanceOf(ApplicationFailure.class);
  }

  @Test
  void fingerprintReflectsRowVersionAndBumpsOnUpdate() {
    // MUST-FIX-8: fingerprint == row version; an UPDATE that bumps version changes it.
    seed(crypto(), "alice", "alice-key", "alice-secret", PAPER_HOST, null, "111", 1);
    String fp1 = source(PAPER).fingerprint("alice", PROVIDER);

    dsl.update(table("broker_credentials"))
        .set(field("version"), 2L)
        .where(field("tenant_id").eq("alice"))
        .execute();
    String fp2 = source(PAPER).fingerprint("alice", PROVIDER);

    assertThat(fp1).isEqualTo("1");
    assertThat(fp2).isEqualTo("2");
    assertThat(fp2).isNotEqualTo(fp1);
  }

  @Test
  void fingerprintAbsentForMissingRow() {
    assertThat(source(PAPER).fingerprint("nobody", PROVIDER)).isEqualTo("absent");
  }

  @Test
  void liveTenantsReturnsDistinctTenantIdsForProvider() {
    seed(crypto(), "alice", "alice-key", "alice-secret", PAPER_HOST, "wss://x", "111", 1);
    seed(crypto(), "bob", "bob-key", "bob-secret", PAPER_HOST, "wss://y", "222", 1);

    assertThat(source(PAPER).liveTenants(PROVIDER)).containsExactlyInAnyOrder("alice", "bob");
  }

  @Test
  void resolveThrownMessageNeverLeaksSecretMaterial() {
    // MUST-FIX-7: even on a decrypt failure the thrown message names nothing of the key/secret.
    seed(
        crypto(), "alice", "SUPER-SECRET-KEY-ID", "SUPER-SECRET-VALUE", PAPER_HOST, null, "111", 1);
    byte[] ct =
        dsl.select(field("ciphertext", byte[].class))
            .from(table("broker_credentials"))
            .where(field("tenant_id").eq("alice"))
            .fetchOne(field("ciphertext", byte[].class));
    ct[0] ^= 0x01;
    dsl.update(table("broker_credentials"))
        .set(field("ciphertext"), ct)
        .where(field("tenant_id").eq("alice"))
        .execute();

    assertThatThrownBy(() -> source(PAPER).resolve("alice", PROVIDER))
        .isInstanceOf(ApplicationFailure.class)
        .hasMessageNotContaining("SUPER-SECRET-KEY-ID")
        .hasMessageNotContaining("SUPER-SECRET-VALUE");
  }
}
