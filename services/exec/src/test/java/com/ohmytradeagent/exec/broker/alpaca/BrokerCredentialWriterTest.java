package com.ohmytradeagent.exec.broker.alpaca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.exec.broker.crypto.BrokerCredentialCrypto;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Unit-pins the INERT P6-b write path's validate-on-entry gate with a {@link MockWebServer}
 * standing in for {@code /v2/account} (no real network) and a jOOQ {@link MockConnection} standing
 * in for the DB (no Testcontainers). The crypto round-trip + CAS-version semantics against the real
 * Flyway schema are pinned in {@code BrokerCredentialWriterIT}; here we pin the money-adjacent
 * invariant: validate-on-entry rejects BEFORE any DB write, so a rejecting save issues NO SQL at
 * all.
 */
class BrokerCredentialWriterTest {

  private static final String PROVIDER = "alpaca";
  private static final int KEK_VERSION = 1;
  private static final byte[] KEK = kek();

  private MockWebServer server;
  private String baseUrl;

  /** Every SQL string the writer issues to the DB, in order. Empty ⇒ no DB write happened. */
  private final List<String> executedSql = new ArrayList<>();

  private final ObjectMapper mapper = new ObjectMapper();
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final RestClient.Builder builder = RestClient.builder();

  private static byte[] kek() {
    byte[] k = new byte[32];
    java.util.Arrays.fill(k, (byte) 0x42);
    return k;
  }

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
    baseUrl = server.url("/").toString().replaceAll("/$", "");
  }

  @AfterEach
  void tearDown() throws IOException {
    server.shutdown();
  }

  /**
   * A jOOQ-backed {@link DSLContext} that records each issued SQL string and returns {@code
   * affectedRows} for any write — no real database. A non-empty {@link #executedSql} after a save
   * means the writer reached the DB; empty means it rejected before any write.
   */
  private DSLContext recordingDsl(int affectedRows) {
    MockDataProvider provider =
        ctx -> {
          executedSql.add(ctx.sql());
          MockResult result = new MockResult(affectedRows, null);
          return new MockResult[] {result};
        };
    MockConnection connection = new MockConnection(provider);
    return DSL.using(connection, SQLDialect.POSTGRES);
  }

  private BrokerCredentialCrypto crypto() {
    return new BrokerCredentialCrypto(Map.of(KEK_VERSION, KEK), KEK_VERSION);
  }

  private BrokerCredentialWriter writer(DSLContext dsl, String brokerImpl) {
    return writer(dsl, brokerImpl, false);
  }

  private BrokerCredentialWriter writer(DSLContext dsl, String brokerImpl, boolean liveEnabled) {
    return writer(dsl, brokerImpl, liveEnabled, false);
  }

  private BrokerCredentialWriter writer(
      DSLContext dsl, String brokerImpl, boolean liveEnabled, boolean deleteLiveEnabled) {
    return new BrokerCredentialWriter(
        dsl, crypto(), builder, mapper, meterRegistry, brokerImpl, liveEnabled, deleteLiveEnabled);
  }

  private void enqueueAccount(String accountNumber) {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"account_number\":\"" + accountNumber + "\",\"equity\":\"1\",\"cash\":\"1\"}"));
  }

  @Test
  void validateOnEntry_matchingDeclaredAccount_savesRow() throws SQLException {
    // The keys authenticate the SAME account the operator declared → save proceeds, write issued.
    enqueueAccount("847309116");

    BrokerCredentialWriter.SaveResult result =
        writer(recordingDsl(1), "alpaca-x")
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

    assertThat(result.version()).isEqualTo(1L);
    // The non-secret SAVED metadata the audit needs: the active KEK version + verified account.
    assertThat(result.kekVersion()).isEqualTo(KEK_VERSION);
    assertThat(result.brokerAccountId()).isEqualTo("847309116");
    // Exactly one SQL write was issued, and it is the upsert into broker_credentials.
    assertThat(executedSql).hasSize(1);
    assertThat(executedSql.get(0).toLowerCase()).contains("broker_credentials");
    // The validate-on-entry probe hit /v2/account exactly once.
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  void validateOnEntry_accountMismatch_rejectsAndWritesNoRow() {
    // The keys authenticate a DIFFERENT account than declared → reject BEFORE any DB write.
    enqueueAccount("999999999");

    assertThatThrownBy(
            () ->
                writer(recordingDsl(1), "alpaca-x")
                    .save(
                        "alice",
                        PROVIDER,
                        "alice-key",
                        "alice-secret",
                        baseUrl,
                        "wss://x",
                        "847309116",
                        0L,
                        "tester"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("mismatch");

    // The money-adjacent invariant: a rejecting save issues NO SQL.
    assertThat(executedSql).isEmpty();
  }

  @Test
  void validateOnEntry_paperLiveHostMismatch_rejectsAndWritesNoRow() {
    // A -paper pod with a live (non-paper) base-url → assertCoherent throws before any account read
    // and before any DB write.
    assertThatThrownBy(
            () ->
                writer(recordingDsl(1), "alpaca-paper")
                    .save(
                        "alice",
                        PROVIDER,
                        "alice-key",
                        "alice-secret",
                        "https://api.alpaca.markets",
                        "wss://x",
                        "847309116",
                        0L,
                        "tester"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("paper endpoint");

    assertThat(executedSql).isEmpty();
    // Coherence fails before the probe → no account read.
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void refuseLivePod_flagOff_throwsByteIdenticalBeforeAnyValidationOrWrite() {
    // Default-off (live-enabled unset): a -live pod refuses to persist DB creds outright, with the
    // exact P6-b message, before any probe or DB write.
    assertThatThrownBy(
            () ->
                writer(recordingDsl(1), "alpaca-live")
                    .save(
                        "alice",
                        PROVIDER,
                        "alice-key",
                        "alice-secret",
                        "https://api.alpaca.markets",
                        "wss://x",
                        "847309116",
                        0L,
                        "tester"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "db-sourced broker credential writes are refused on a -live pod in P6-b (tenant=alice"
                + " provider=alpaca)");

    assertThat(executedSql).isEmpty();
    // Refused by construction → no account read, no DB write.
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void livePod_flagOn_boundAccountAndValidIdentity_savesRow() {
    // Opt-in on a -live pod: the keys authenticate the declared account → validate-on-entry passes
    // and the row is persisted.
    enqueueAccount("847309116");

    // The mock-server baseUrl carries no "paper" token, so it stays coherent with a -live impl.
    BrokerCredentialWriter.SaveResult result =
        writer(recordingDsl(1), "alpaca-live", true)
            .save(
                "alice",
                PROVIDER,
                "alice-key",
                "alice-secret",
                baseUrl,
                "wss://live",
                "847309116",
                0L,
                "tester");

    assertThat(result.version()).isEqualTo(1L);
    assertThat(result.brokerAccountId()).isEqualTo("847309116");
    assertThat(executedSql).hasSize(1);
    assertThat(executedSql.get(0).toLowerCase()).contains("broker_credentials");
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  void livePod_flagOn_blankDeclaredAccount_rejectsAndWritesNoRow() {
    // The live seal: on a -live pod a blank declaredAccountId would no-op the identity probe, so it
    // must fail closed before any probe or DB write — nothing persists.
    assertThatThrownBy(
            () ->
                writer(recordingDsl(1), "alpaca-live", true)
                    .save(
                        "alice",
                        PROVIDER,
                        "alice-key",
                        "alice-secret",
                        "https://api.alpaca.markets",
                        "wss://live",
                        "  ",
                        0L,
                        "tester"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("declaredAccountId")
        .hasMessageContaining("a -live target must declare its expected account");

    assertThat(executedSql).isEmpty();
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void livePod_flagOn_nullDeclaredAccount_rejectsAndWritesNoRow() {
    // A null declaredAccountId must also fail closed on a -live pod.
    assertThatThrownBy(
            () ->
                writer(recordingDsl(1), "alpaca-live", true)
                    .save(
                        "alice",
                        PROVIDER,
                        "alice-key",
                        "alice-secret",
                        "https://api.alpaca.markets",
                        "wss://live",
                        null,
                        0L,
                        "tester"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("declaredAccountId");

    assertThat(executedSql).isEmpty();
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void delete_issuesDeleteSql_andReturnsAffectedCount() {
    // The delete is a single parameterized DELETE against broker_credentials; the affected-row
    // count
    // is returned verbatim. No probe, no key material.
    int deleted = writer(recordingDsl(1), "alpaca-x").delete("alice", PROVIDER);

    assertThat(deleted).isEqualTo(1);
    assertThat(executedSql).hasSize(1);
    assertThat(executedSql.get(0).toLowerCase()).contains("delete from broker_credentials");
    // Delete never authenticates against the broker — no /v2/account probe.
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void delete_absentRow_returnsZero_doesNotThrow() {
    // Idempotent: deleting a row that is not there returns 0 and does NOT throw.
    int deleted = writer(recordingDsl(0), "alpaca-x").delete("nobody", PROVIDER);

    assertThat(deleted).isZero();
    assertThat(executedSql).hasSize(1);
  }

  @Test
  void delete_onLivePod_withoutDeleteLiveEnabled_throws_andIssuesNoSql() {
    // Defense-in-depth -live seal on the destructive path: a -live pod refuses a DB credential
    // delete unless the dedicated broker.credentials.delete.live-enabled opt-in is set. Default off
    // → fail closed with NO SQL issued (no DELETE reaches the DB).
    assertThatThrownBy(() -> writer(recordingDsl(1), "alpaca-live").delete("alice", PROVIDER))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("broker.credentials.delete.live-enabled")
        .hasMessageContaining("-live");

    assertThat(executedSql).isEmpty();
  }

  @Test
  void delete_onLivePod_withDeleteLiveEnabled_issuesDelete() {
    // Opt-in on a -live pod (the dedicated delete flag on) → the DELETE is issued and the affected
    // count is returned verbatim.
    int deleted = writer(recordingDsl(1), "alpaca-live", false, true).delete("alice", PROVIDER);

    assertThat(deleted).isEqualTo(1);
    assertThat(executedSql).hasSize(1);
    assertThat(executedSql.get(0).toLowerCase()).contains("delete from broker_credentials");
  }

  @Test
  void thrownMessageNeverLeaksKeyMaterial() {
    // MUST-FIX-7: a rejecting save's thrown message names nothing of the key/secret.
    enqueueAccount("999999999");

    assertThatThrownBy(
            () ->
                writer(recordingDsl(1), "alpaca-x")
                    .save(
                        "alice",
                        PROVIDER,
                        "SUPER-SECRET-KEY-ID",
                        "SUPER-SECRET-VALUE",
                        baseUrl,
                        "wss://x",
                        "847309116",
                        0L,
                        "tester"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("SUPER-SECRET-KEY-ID")
        .hasMessageNotContaining("SUPER-SECRET-VALUE");
  }
}
