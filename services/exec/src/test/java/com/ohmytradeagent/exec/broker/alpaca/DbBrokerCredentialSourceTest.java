package com.ohmytradeagent.exec.broker.alpaca;

import static com.ohmytradeagent.exec.broker.crypto.BrokerCredentialCrypto.aad;
import static com.ohmytradeagent.exec.broker.crypto.BrokerCredentialCrypto.pack;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;

import com.ohmytradeagent.exec.broker.BrokerClientRegistry;
import com.ohmytradeagent.exec.broker.BrokerCredentials;
import com.ohmytradeagent.exec.broker.crypto.BrokerCredentialCrypto;
import io.temporal.failure.ApplicationFailure;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test (no DB) for the Phase-1 {@code broker.creds.db.live-enabled} gate on {@link
 * DbBrokerCredentialSource}. The DB is stubbed with jOOQ's {@link MockConnection} so these run
 * under {@code mvn test} (the full read/decrypt matrix stays in the Testcontainers {@code
 * DbBrokerCredentialSourceIT}).
 *
 * <p>Covers: a {@code -live} pod refuses byte-identically to P6-a when the flag is off (and never
 * touches the DB); serves a live row only when the flag is on AND the row declares a bound {@code
 * expected_account_id}; fails closed on a blank/null {@code expected_account_id} — including via
 * the {@code ACCOUNT_LEVEL} sentinel path; and leaves the paper path unchanged.
 */
class DbBrokerCredentialSourceTest {

  private static final String PROVIDER = "alpaca";
  private static final String LIVE = "alpaca-live";
  private static final String PAPER = "alpaca-paper";
  private static final String ACCT_TENANT = "acct-level-tenant";
  private static final String PAPER_HOST = "https://paper-api.alpaca.markets";
  private static final String LIVE_HOST = "https://api.alpaca.markets";
  private static final int KEK_VERSION = 1;

  // Column definitions mirror the source's projection (matched by name in the mock result).
  private static final Field<byte[]> CIPHERTEXT = field("ciphertext", byte[].class);
  private static final Field<byte[]> IV = field("iv", byte[].class);
  private static final Field<byte[]> WRAPPED_DEK = field("wrapped_dek", byte[].class);
  private static final Field<byte[]> DEK_IV = field("dek_iv", byte[].class);
  private static final Field<Integer> KEK_VERSION_F = field("kek_version", Integer.class);
  private static final Field<String> BASE_URL = field("base_url", String.class);
  private static final Field<String> WS_URL = field("ws_url", String.class);
  private static final Field<String> EXPECTED_ACCOUNT_ID =
      field("expected_account_id", String.class);

  @Test
  void livePodWithFlagOffRefusesByteIdenticalAndReadsNoRow() {
    // Default-off: a -live pod refuses exactly as P6-a did, and short-circuits BEFORE any SELECT.
    AtomicInteger queries = new AtomicInteger();
    DbBrokerCredentialSource src = source(LIVE, false, noRowDsl(queries));

    assertThatThrownBy(() -> src.resolve("alice", PROVIDER))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            af -> {
              assertThat(af.getType()).isEqualTo("BrokerCredentialsUnavailable");
              assertThat(af.isNonRetryable()).isTrue();
            })
        .hasMessageContaining(
            "db-sourced broker credentials are refused on a -live pod in P6-a (tenant=alice"
                + " provider=alpaca)");
    assertThat(queries.get()).isZero();
  }

  @Test
  void livePodWithFlagOnAndBoundAccountResolves() {
    BrokerCredentialCrypto.Envelope env = envelope("alice", "live-key", "live-secret", "847309116");
    DbBrokerCredentialSource src =
        source(LIVE, true, rowDsl(env, LIVE_HOST, "wss://live", "847309116"));

    BrokerCredentials c = src.resolve("alice", PROVIDER);

    assertThat(c.apiKeyId()).isEqualTo("live-key");
    assertThat(c.apiSecretKey()).isEqualTo("live-secret");
    assertThat(c.baseUrl()).isEqualTo(LIVE_HOST);
    assertThat(c.wsUrl()).isEqualTo("wss://live");
    assertThat(c.expectedAccountId()).isEqualTo("847309116");
  }

  @Test
  void twoLiveTenantsEachResolveTheirOwnDistinctRow() {
    // Multi-live-account guard (fleet enablement Phase 2): two live tenants with DISTINCT non-blank
    // expected_account_id each resolve THEIR OWN row (keyed on tenant_id in the WHERE + the AAD).
    // A cross-wire — alice's key served for bob — would double-size one account under the shared
    // exec pod, so this pins that resolve is strictly per-tenant.
    Row alice = new Row(envelope("alice", "alice-key", "alice-secret", "111"), LIVE_HOST, "111");
    Row bob = new Row(envelope("bob", "bob-key", "bob-secret", "222"), LIVE_HOST, "222");
    DbBrokerCredentialSource src =
        source(LIVE, true, multiTenantDsl(Map.of("alice", alice, "bob", bob)));

    BrokerCredentials a = src.resolve("alice", PROVIDER);
    assertThat(a.apiKeyId()).isEqualTo("alice-key");
    assertThat(a.apiSecretKey()).isEqualTo("alice-secret");
    assertThat(a.expectedAccountId()).isEqualTo("111");

    BrokerCredentials b = src.resolve("bob", PROVIDER);
    assertThat(b.apiKeyId()).isEqualTo("bob-key");
    assertThat(b.apiSecretKey()).isEqualTo("bob-secret");
    assertThat(b.expectedAccountId()).isEqualTo("222");
  }

  @Test
  void livePodWithFlagOnAndBlankExpectedAccountFailsClosed() {
    // The row decrypts fine, but a blank expected_account_id must never serve a live credential.
    BrokerCredentialCrypto.Envelope env = envelope("alice", "live-key", "live-secret", "");
    DbBrokerCredentialSource src = source(LIVE, true, rowDsl(env, LIVE_HOST, "wss://live", ""));

    assertThatThrownBy(() -> src.resolve("alice", PROVIDER))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            af -> assertThat(af.getType()).isEqualTo("BrokerCredentialsUnavailable"))
        .hasMessageContaining("expected_account_id")
        .hasMessageContaining("a -live target must declare its expected account");
  }

  @Test
  void livePodWithFlagOnAndWhitespaceOnlyExpectedAccountFailsClosed() {
    // Whitespace-only expected_account_id must ALSO fail closed: the seal uses isBlank() (not
    // isEmpty()) to match BrokerAccountIdentityVerifier.verify()'s no-op predicate EXACTLY —
    // otherwise "  " slips the seal (isEmpty()==false) yet skips identity verification
    // (isBlank()==true), serving a live credential with no account binding.
    BrokerCredentialCrypto.Envelope env = envelope("alice", "live-key", "live-secret", "   ");
    DbBrokerCredentialSource src = source(LIVE, true, rowDsl(env, LIVE_HOST, "wss://live", "   "));

    assertThatThrownBy(() -> src.resolve("alice", PROVIDER))
        .isInstanceOf(ApplicationFailure.class)
        .hasMessageContaining("expected_account_id");
  }

  @Test
  void livePodWithFlagOnAndNullExpectedAccountFailsClosed() {
    // A null column coalesces to "" and must also fail closed.
    BrokerCredentialCrypto.Envelope env = envelope("alice", "live-key", "live-secret", "");
    DbBrokerCredentialSource src = source(LIVE, true, rowDsl(env, LIVE_HOST, "wss://live", null));

    assertThatThrownBy(() -> src.resolve("alice", PROVIDER))
        .isInstanceOf(ApplicationFailure.class)
        .hasMessageContaining("expected_account_id");
  }

  @Test
  void livePodWithFlagOnAccountLevelSentinelBlankExpectedFailsClosed() {
    // C2: the blank seal must cover the ACCOUNT_LEVEL sentinel path (resolved to ACCT_TENANT), not
    // just a real tenant id — otherwise BrokerAccountIdentityVerifier.verify() no-ops on blank and
    // identity verification is silently disabled for account-level live reads.
    BrokerCredentialCrypto.Envelope env = envelope(ACCT_TENANT, "acct-key", "acct-secret", "");
    DbBrokerCredentialSource src = source(LIVE, true, rowDsl(env, LIVE_HOST, "wss://live", ""));

    assertThatThrownBy(() -> src.resolve(BrokerClientRegistry.ACCOUNT_LEVEL, PROVIDER))
        .isInstanceOf(ApplicationFailure.class)
        .hasMessageContaining("expected_account_id");
  }

  @Test
  void paperPodResolvesUnchangedRegardlessOfLiveFlag() {
    // The paper path never consults the live gate: a blank expected_account_id is allowed and the
    // credential resolves byte-identically to today.
    BrokerCredentialCrypto.Envelope env = envelope("alice", "paper-key", "paper-secret", "");
    DbBrokerCredentialSource src = source(PAPER, false, rowDsl(env, PAPER_HOST, "wss://paper", ""));

    BrokerCredentials c = src.resolve("alice", PROVIDER);

    assertThat(c.apiKeyId()).isEqualTo("paper-key");
    assertThat(c.expectedAccountId()).isEmpty();
    assertThat(c.baseUrl()).isEqualTo(PAPER_HOST);
  }

  // --- helpers -----------------------------------------------------------------------------------

  private static DbBrokerCredentialSource source(
      String brokerImpl, boolean liveEnabled, DSLContext dsl) {
    return new DbBrokerCredentialSource(dsl, crypto(), brokerImpl, ACCT_TENANT, liveEnabled);
  }

  private static BrokerCredentialCrypto crypto() {
    // Deterministic KEK: separate instances share the same key bytes, so a row encrypted by one
    // test instance decrypts under the source's instance.
    byte[] kek = new byte[32];
    Arrays.fill(kek, (byte) 0x42);
    return new BrokerCredentialCrypto(Map.of(KEK_VERSION, kek), KEK_VERSION);
  }

  private static BrokerCredentialCrypto.Envelope envelope(
      String tenant, String keyId, String secret, String expectedForAad) {
    return crypto()
        .encrypt(
            pack(keyId.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8)),
            aad(tenant, PROVIDER, expectedForAad == null ? "" : expectedForAad, KEK_VERSION));
  }

  /** A DSLContext whose provider returns one credential row for any query. */
  private static DSLContext rowDsl(
      BrokerCredentialCrypto.Envelope env, String baseUrl, String wsUrl, String expected) {
    DSLContext render = DSL.using(SQLDialect.POSTGRES);
    // Explicit Field<?>[] forces the varargs newRecord/newResult overloads (Result<Record>) rather
    // than the typed Record8 overloads.
    Field<?>[] cols = {
      CIPHERTEXT, IV, WRAPPED_DEK, DEK_IV, KEK_VERSION_F, BASE_URL, WS_URL, EXPECTED_ACCOUNT_ID
    };
    Record r = render.newRecord(cols);
    r.set(CIPHERTEXT, env.ciphertext());
    r.set(IV, env.iv());
    r.set(WRAPPED_DEK, env.wrappedDek());
    r.set(DEK_IV, env.dekIv());
    r.set(KEK_VERSION_F, env.kekVersion());
    r.set(BASE_URL, baseUrl);
    r.set(WS_URL, wsUrl);
    r.set(EXPECTED_ACCOUNT_ID, expected);
    Result<Record> result = render.newResult(cols);
    result.add(r);
    MockResult[] canned = {new MockResult(1, result)};
    return DSL.using(new MockConnection(ctx -> canned), SQLDialect.POSTGRES);
  }

  /** A single credential row keyed by tenant in {@link #multiTenantDsl}. */
  private record Row(BrokerCredentialCrypto.Envelope env, String baseUrl, String expected) {}

  /**
   * A DSLContext that returns a DIFFERENT credential row per tenant_id — routed by the {@code WHERE
   * tenant_id = ?} bind value. Proves the source resolves strictly per-tenant when several live
   * tenants share the pod's exec DB.
   */
  private static DSLContext multiTenantDsl(Map<String, Row> byTenant) {
    DSLContext render = DSL.using(SQLDialect.POSTGRES);
    Field<?>[] cols = {
      CIPHERTEXT, IV, WRAPPED_DEK, DEK_IV, KEK_VERSION_F, BASE_URL, WS_URL, EXPECTED_ACCOUNT_ID
    };
    MockDataProvider provider =
        ctx -> {
          Row row = null;
          for (Object bind : ctx.bindings()) {
            if (bind instanceof String s && byTenant.containsKey(s)) {
              row = byTenant.get(s);
              break;
            }
          }
          Result<Record> result = render.newResult(cols);
          if (row != null) {
            Record r = render.newRecord(cols);
            r.set(CIPHERTEXT, row.env().ciphertext());
            r.set(IV, row.env().iv());
            r.set(WRAPPED_DEK, row.env().wrappedDek());
            r.set(DEK_IV, row.env().dekIv());
            r.set(KEK_VERSION_F, row.env().kekVersion());
            r.set(BASE_URL, row.baseUrl());
            r.set(WS_URL, "wss://" + row.expected());
            r.set(EXPECTED_ACCOUNT_ID, row.expected());
            result.add(r);
          }
          return new MockResult[] {new MockResult(row == null ? 0 : 1, result)};
        };
    return DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
  }

  /** A DSLContext that records whether any query ran (used to prove the flag-off short-circuit). */
  private static DSLContext noRowDsl(AtomicInteger queries) {
    MockDataProvider provider =
        ctx -> {
          queries.incrementAndGet();
          return new MockResult[] {new MockResult(0, DSL.using(SQLDialect.POSTGRES).newResult())};
        };
    return DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
  }
}
