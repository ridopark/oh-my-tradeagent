package com.ohmytradeagent.exec.broker.alpaca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.exec.broker.BrokerCredentials;
import com.ohmytradeagent.exec.broker.crypto.BrokerCredentialCrypto;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.sql.DriverManager;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Testcontainers Postgres + Flyway IT for the INERT P6-b write path. Drives the validate-on-entry
 * probe with a {@link MockWebServer} {@code /v2/account} (so no real broker), then persists via the
 * CAS upsert and proves: first write inserts {@code version=1}; a second save bumps the version and
 * re-encrypts (new ciphertext bytes — fresh DEK/nonce per write); a stale {@code expectedVersion}
 * fails the CAS with {@link OptimisticLockException}; and a writer-encrypt → {@link
 * DbBrokerCredentialSource} decrypt round-trip returns the exact keys (proves the read+write share
 * a byte-identical AAD + envelope). Gated on {@code RUN_DB_ITS=true} (mirrors {@code
 * DbBrokerCredentialSourceIT}).
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class BrokerCredentialWriterIT {

  private static final String PROVIDER = "alpaca";
  private static final int KEK_VERSION = 1;
  private static final String IMPL = "alpaca-x"; // neither -paper nor -live → coherence inert

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  private static java.sql.Connection conn;
  private static DSLContext dsl;
  private static byte[] kekBytes;

  private MockWebServer server;
  private String baseUrl;

  private final ObjectMapper mapper = new ObjectMapper();
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final RestClient.Builder builder = RestClient.builder();

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
  void start() throws IOException {
    server = new MockWebServer();
    server.start();
    baseUrl = server.url("/").toString().replaceAll("/$", "");
    dsl.deleteFrom(table("broker_credentials")).execute();
  }

  @AfterEach
  void stop() throws IOException {
    server.shutdown();
  }

  private BrokerCredentialCrypto crypto() {
    return cryptoAtVersion(KEK_VERSION);
  }

  /** A crypto bean whose ACTIVE (encrypt-time) KEK version is {@code activeVersion}. */
  private BrokerCredentialCrypto cryptoAtVersion(int activeVersion) {
    return new BrokerCredentialCrypto(Map.of(activeVersion, kekBytes), activeVersion);
  }

  private BrokerCredentialWriter writer() {
    return writer(crypto());
  }

  private BrokerCredentialWriter writer(BrokerCredentialCrypto crypto) {
    return new BrokerCredentialWriter(dsl, crypto, builder, mapper, meterRegistry, IMPL);
  }

  /** The DB read sibling; shares the crypto so the round-trip proves AAD + envelope agree. */
  private DbBrokerCredentialSource source() {
    return source(crypto());
  }

  private DbBrokerCredentialSource source(BrokerCredentialCrypto crypto) {
    // This IT exercises real tenants directly, so the account-level-tenant mapping is unused here.
    return new DbBrokerCredentialSource(dsl, crypto, IMPL, "acct-level-tenant", false);
  }

  private void enqueueAccount(String accountNumber) {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"account_number\":\"" + accountNumber + "\",\"equity\":\"1\",\"cash\":\"1\"}"));
  }

  private byte[] ciphertextOf(String tenant) {
    return dsl.select(field("ciphertext", byte[].class))
        .from(table("broker_credentials"))
        .where(field("tenant_id").eq(tenant))
        .fetchOne(field("ciphertext", byte[].class));
  }

  private long versionOf(String tenant) {
    return dsl.select(field("version", Long.class))
        .from(table("broker_credentials"))
        .where(field("tenant_id").eq(tenant))
        .fetchOne(field("version", Long.class));
  }

  @Test
  void firstWriteInsertsVersionOne() {
    enqueueAccount("847309116");

    BrokerCredentialWriter.SaveResult result =
        writer().save("alice", PROVIDER, "k1", "s1", baseUrl, "wss://x", "847309116", 0L, "tester");

    assertThat(result.version()).isEqualTo(1L);
    assertThat(versionOf("alice")).isEqualTo(1L);
    // The widened SaveResult carries the active KEK version and the stubbed /v2/account number.
    assertThat(result.kekVersion()).isEqualTo(crypto().activeVersion());
    assertThat(result.brokerAccountId()).isEqualTo("847309116");
  }

  @Test
  void secondSaveBumpsVersionAndReEncrypts() {
    enqueueAccount("847309116");
    long v1 =
        writer()
            .save("alice", PROVIDER, "k1", "s1", baseUrl, "wss://x", "847309116", 0L, "tester")
            .version();
    byte[] ct1 = ciphertextOf("alice");

    enqueueAccount("847309116");
    long v2 =
        writer()
            .save("alice", PROVIDER, "k2", "s2", baseUrl, "wss://x", "847309116", v1, "tester")
            .version();
    byte[] ct2 = ciphertextOf("alice");

    assertThat(v1).isEqualTo(1L);
    assertThat(v2).isEqualTo(2L);
    assertThat(versionOf("alice")).isEqualTo(2L);
    // Always re-encrypt fresh (new DEK + nonces) → ciphertext bytes differ even for the same row.
    assertThat(ct2).isNotEqualTo(ct1);
  }

  @Test
  void staleExpectedVersionFailsCas() {
    enqueueAccount("847309116");
    writer().save("alice", PROVIDER, "k1", "s1", baseUrl, "wss://x", "847309116", 0L, "tester");

    // The row is now at version=1; a second save claiming expectedVersion=0 must lose the CAS.
    enqueueAccount("847309116");
    assertThatThrownBy(
            () ->
                writer()
                    .save("alice", PROVIDER, "k2", "s2", baseUrl, "wss://x", "847309116", 0L, "x"))
        .isInstanceOf(OptimisticLockException.class);

    // The losing write left the row untouched at version 1.
    assertThat(versionOf("alice")).isEqualTo(1L);
  }

  @Test
  void preExistingRowFailsFirstWriteCas() {
    enqueueAccount("847309116");
    writer().save("alice", PROVIDER, "k1", "s1", baseUrl, "wss://x", "847309116", 0L, "tester");

    // A blind "first write" (expectedVersion=0) against an already-present row must fail the CAS.
    enqueueAccount("847309116");
    assertThatThrownBy(
            () ->
                writer()
                    .save("alice", PROVIDER, "k9", "s9", baseUrl, "wss://x", "847309116", 0L, "x"))
        .isInstanceOf(OptimisticLockException.class);
  }

  @Test
  void roundTripWriteThenReadReturnsExactKeys() {
    // The load-bearing cross-path proof: the writer's AAD + envelope must round-trip with the read
    // sibling's decrypt. base_url is the mock URL the writer persisted; resolve returns it
    // verbatim.
    enqueueAccount("847309116");
    writer()
        .save(
            "alice",
            PROVIDER,
            "alice-key",
            "alice-secret",
            baseUrl,
            "wss://x",
            "847309116",
            0L,
            "tester");

    BrokerCredentials c = source().resolve("alice", PROVIDER);

    assertThat(c.apiKeyId()).isEqualTo("alice-key");
    assertThat(c.apiSecretKey()).isEqualTo("alice-secret");
    assertThat(c.baseUrl()).isEqualTo(baseUrl);
    assertThat(c.wsUrl()).isEqualTo("wss://x");
    assertThat(c.expectedAccountId()).isEqualTo("847309116");
  }

  @Test
  void brokerTargetProviderIsCanonicalizedToProvider() {
    // A caller passing a broker_target-style provider ("alpaca-paper") must persist under the
    // read-path provider authority ("alpaca"). Otherwise the stored row + its AAD key on
    // "alpaca-paper" while resolve(tenant, providerOf("alpaca-paper")=="alpaca") looks up "alpaca"
    // → unresolvable (and AAD/GCM-fail even if found).
    enqueueAccount("847309116");
    writer()
        .save("alice", "alpaca-paper", "k1", "s1", baseUrl, "wss://x", "847309116", 0L, "tester");

    // The row is keyed by the canonical provider "alpaca" at version 1 ...
    Long canonicalVersion =
        dsl.select(field("version", Long.class))
            .from(table("broker_credentials"))
            .where(field("tenant_id").eq("alice"))
            .and(field("provider").eq("alpaca"))
            .fetchOne(field("version", Long.class));
    assertThat(canonicalVersion).isEqualTo(1L);

    // ... and NOT under the broker_target value "alpaca-paper".
    Integer brokerTargetRows =
        dsl.selectCount()
            .from(table("broker_credentials"))
            .where(field("tenant_id").eq("alice"))
            .and(field("provider").eq("alpaca-paper"))
            .fetchOne(0, Integer.class);
    assertThat(brokerTargetRows).isZero();

    // Bonus: it resolves (and decrypts) under the canonical provider "alpaca", proving the
    // write-AAD
    // bound the canonical provider so it matches the read-AAD.
    BrokerCredentials c = source().resolve("alice", "alpaca");
    assertThat(c.apiKeyId()).isEqualTo("k1");
    assertThat(c.apiSecretKey()).isEqualTo("s1");
  }

  @Test
  void roundTripAtNonDefaultActiveKekVersion() {
    // Regression for the AAD-kek_version divergence bug: the writer must derive the AAD's
    // kek_version from the crypto bean's ACTIVE version (not a hardcoded field), so the stored
    // kek_version column, the write-AAD, and the read-AAD all agree at ANY active version. With a
    // hardcoded writer kek_version=1, an activeVersion=7 crypto would stamp the column/read-AAD
    // with
    // 7 while the write-AAD bound 1 → GCM auth failure on resolve. Prove the round-trip holds at 7.
    int activeVersion = 7;
    BrokerCredentialCrypto crypto = cryptoAtVersion(activeVersion);

    enqueueAccount("847309116");
    writer(crypto)
        .save(
            "alice",
            PROVIDER,
            "alice-key",
            "alice-secret",
            baseUrl,
            "wss://x",
            "847309116",
            0L,
            "tester");

    // The row was stamped with the active KEK version, not the default.
    int storedKekVersion =
        dsl.select(field("kek_version", Integer.class))
            .from(table("broker_credentials"))
            .where(field("tenant_id").eq("alice"))
            .fetchOne(field("kek_version", Integer.class));
    assertThat(storedKekVersion).isEqualTo(activeVersion);

    // The read sibling (same active version) decrypts cleanly → write-AAD == read-AAD at v7.
    BrokerCredentials c = source(crypto).resolve("alice", PROVIDER);
    assertThat(c.apiKeyId()).isEqualTo("alice-key");
    assertThat(c.apiSecretKey()).isEqualTo("alice-secret");
  }
}
