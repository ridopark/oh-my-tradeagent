package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.orchestrator.alert.OrderFailureAlerter;
import com.ohmytradeagent.orchestrator.alert.TenantWebhookResolver;
import com.ohmytradeagent.orchestrator.alert.WebhookClient;
import com.ohmytradeagent.orchestrator.alert.WebhookEmbed;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Issue #85 / #119: Spring-aware Testcontainers IT for {@link AuditActivitiesImpl}'s hash-chain
 * INSERT path.
 *
 * <ul>
 *   <li>Boots a minimal Spring context ({@code @SpringBootTest(webEnvironment = NONE)} with a
 *       narrow {@code @Configuration} that pulls in just the auto-configurations needed for the
 *       DataSource + jOOQ DSLContext + transaction-management AOP proxy) so the
 *       {@code @Transactional} advice on {@link AuditActivitiesImpl#log(AuditEvent)} is actually
 *       active. The pre-#119 shape constructed {@code AuditActivitiesImpl} via {@code new}, which
 *       bypassed Spring's proxy and meant the production transaction boundary was never exercised.
 *   <li>Connects the runtime DataSource as the {@code orchestrator_runtime} role (V4) so the V3
 *       REVOKE binding is exercised end-to-end. Flyway runs as the container superuser via the
 *       direct {@code adminUrl} below, then sets the runtime role's password, then Spring boots
 *       Hikari pointed at {@code orchestrator_runtime}.
 *   <li>{@code @BeforeEach} cleans the table via the admin connection so tests are isolated and
 *       order-independent (the pre-#119 shape used {@code @BeforeAll}-style cleanup which leaked
 *       chain state across tests).
 *   <li>Tests carry explicit {@code @Order} so the {@code writesGoldenVectorChainAndRoundTrips}
 *       insertion order is deterministic regardless of JUnit's default discovery ordering.
 * </ul>
 *
 * <p>Gated on {@code RUN_DB_ITS=true} to match {@code OrchestratorRuntimeRoleIT} convention —
 * Testcontainers requires Docker, which CI runners may not provide.
 */
@SpringBootTest(
    classes = AuditLogChainWriterIT.AuditITConfig.class,
    webEnvironment = WebEnvironment.NONE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class AuditLogChainWriterIT {

  // Postgres 16 matches the homelab image, OrchestratorRuntimeRoleIT, and DailyPnlActivitiesImplIT.
  private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  private static final String RUNTIME_PASSWORD = "it-test-pw";

  /** Admin (container-superuser) connection used by {@code @BeforeEach} cleanup + read-back. */
  private static Connection adminConn;

  /**
   * Minimal Spring boot config for this IT. Pulls in only the autoconfigurations needed to wire a
   * {@code DataSource}, jOOQ {@code DSLContext}, transaction manager, and the AOP proxy that
   * applies {@code @Transactional} advice — without booting Temporal, Redis, schedulers, or any of
   * the other orchestrator runtime infrastructure that would require external dependencies during
   * an IT run.
   */
  @Configuration
  @EnableAutoConfiguration
  @EnableTransactionManagement
  static class AuditITConfig {

    // Issue #1029 Step 2: mock seams for the funnel IT — one WebhookClient to capture the Discord
    // embed, one TenantWebhookResolver stubbed to return a fixed non-blank URL so postEmbedToUrl
    // is actually invoked. Declared as static fields so the same instances are reachable both from
    // the @Bean declarations (which pass them to OrderFailureAlerter) and from the @Autowired test
    // fields (which assert on them).
    private static final WebhookClient mockWebhookClient;
    private static final TenantWebhookResolver mockWebhookResolver;

    static {
      mockWebhookClient = mock(WebhookClient.class);
      mockWebhookResolver = mock(TenantWebhookResolver.class);
      when(mockWebhookResolver.resolve(any(), any())).thenReturn("https://discord.test/webhook/it");
    }

    @Bean
    WebhookClient webhookClient() {
      return mockWebhookClient;
    }

    /**
     * Issue #1029 Step 2: wire the production after-commit alerter using the mock seams above. Uses
     * the 5-arg constructor directly (not @Autowired) to avoid pulling in the full orchestrator
     * Spring configuration (no live DB config needed for the resolver).
     *
     * <p>{@code DEFAULT_FAILURE_KINDS} is package-private in the {@code alert} package and not
     * accessible here; passing {@code "FloorBreachAlerted"} is sufficient — this test only needs
     * the alerter to dispatch on that kind; the full default-kinds coverage is the responsibility
     * of {@code OrderFailureAlerterTest} in the {@code alert} package.
     */
    @Bean
    OrderFailureAlerter orderFailureAlerter() {
      return new OrderFailureAlerter(
          mockWebhookClient,
          mockWebhookResolver,
          /* failureKinds= */ "FloorBreachAlerted",
          /* signalFeedEnabled= */ false,
          /* floorBreachLiveUrl= */ "");
    }

    @Bean
    ObjectMapper objectMapper() {
      // Match the production ObjectMapper shape (JavaTimeModule registered) so OffsetDateTime
      // round-trips on the AuditEvent deserialize path.
      return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Bean
    AuditLogChainWriter auditLogChainWriter(ObjectMapper objectMapper) {
      return new AuditLogChainWriter(objectMapper);
    }

    /**
     * Explicit AuditActivitiesImpl bean (rather than @ComponentScan) keeps this IT context narrow —
     * a broad component scan would pull in TemporalWorkerConfig, KillSwitchBootstrapper, and
     * other @Components that require external services (Temporal, Redis) we deliberately do not
     * boot for this IT. Declaring the bean by hand here is the production-equivalent injection
     * path: Spring still wraps it with the @Transactional AOP proxy
     * because @EnableTransactionManagement is active and AuditActivitiesImpl.log() carries
     * the @Transactional annotation.
     *
     * <p>Issue #1029 Step 2: inject the context's ApplicationEventPublisher (rather than the
     * noopEventPublisher used in the pre-#1029 shape) so the @TransactionalEventListener on
     * OrderFailureAlerter fires for FloorBreachAlerted events. The existing tests use event kinds
     * not in the failure allowlist so OrderFailureAlerter.onAuditEvent() returns immediately for
     * them — no existing assertion is disturbed by this swap.
     */
    @Bean
    AuditActivitiesImpl auditActivities(
        org.jooq.DSLContext dsl,
        ObjectMapper objectMapper,
        AuditLogChainWriter chainWriter,
        @org.springframework.beans.factory.annotation.Value("${audit.chain-writer.enabled:true}")
            boolean chainWriterEnabled,
        ApplicationEventPublisher publisher) {
      return new AuditActivitiesImpl(dsl, objectMapper, chainWriter, chainWriterEnabled, publisher);
    }
  }

  /**
   * Start the container + run Flyway migrations + set the runtime-role password BEFORE Spring's
   * {@code DynamicPropertySource} fires (Spring invokes the registry callback during context
   * initialization, which happens after static initializers but before bean construction). The
   * properties below then point Hikari at the {@code orchestrator_runtime} role.
   */
  static {
    postgres.start();
    try {
      Flyway.configure()
          .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
          .locations("classpath:db/migration")
          .load()
          .migrate();
      try (Connection c =
              DriverManager.getConnection(
                  postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
          Statement st = c.createStatement()) {
        st.execute("ALTER ROLE orchestrator_runtime PASSWORD '" + RUNTIME_PASSWORD + "'");
      }
    } catch (Exception e) {
      throw new RuntimeException("IT bootstrap failed (Flyway + role password setup)", e);
    }
  }

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    // Hikari + Spring DataSource — point at the orchestrator_runtime role so the V3 REVOKE
    // binding is exercised under the production credential path.
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "orchestrator_runtime");
    registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
    // Disable Flyway in the Spring context — we already ran migrations above as superuser, and
    // orchestrator_runtime lacks the privileges Flyway needs.
    registry.add("spring.flyway.enabled", () -> "false");
    // Keep the chain writer enabled for all tests except the disabled-flag test below, which
    // constructs its own AuditActivitiesImpl with chainWriterEnabled=false.
    registry.add("audit.chain-writer.enabled", () -> "true");
  }

  @Autowired private AuditActivitiesImpl activities;
  @Autowired private ObjectMapper om;
  @Autowired private AuditLogChainWriter chainWriter;
  @Autowired private org.jooq.DSLContext dsl;
  // Issue #1029 Step 2: the mock webhook client exposed as a Spring bean for assertion in the
  // floor-breach funnel IT. Reset in @BeforeEach so each test sees a clean call count.
  @Autowired private WebhookClient webhookClient;

  @BeforeAll
  static void openAdminConnection() throws Exception {
    adminConn =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  @AfterAll
  static void closeAdminConnection() throws Exception {
    if (adminConn != null) adminConn.close();
    postgres.stop();
  }

  /**
   * Truncate the audit log via the admin connection before every test so each test sees an empty
   * chain. The runtime role lacks DELETE per V3, so admin must do the scrub. Replaces the pre-#119
   * per-test {@code DELETE WHERE tenant=...} which leaked rows across tests (e.g. the {@code
   * runtimeRoleStillCannot...} test depends on a row being present, which the prior insertion left
   * lying around).
   */
  @BeforeEach
  void cleanAuditLog() throws Exception {
    try (Statement st = adminConn.createStatement()) {
      st.executeUpdate("DELETE FROM audit_log");
    }
    // Reset the mock webhook client before each test so the floor-breach funnel IT (@Order(6))
    // sees a clean call count regardless of whether an earlier test accidentally triggered it.
    // Only webhookClient is reset; AuditITConfig.mockWebhookResolver is not reset so its
    // resolve() stubbing (set once in AuditITConfig's static initializer) stays in place.
    reset(webhookClient);
  }

  @Test
  @Order(1)
  void writesGoldenVectorChainAndRoundTrips() throws Exception {
    JsonNode fixture;
    try (InputStream is =
        AuditLogChainWriterIT.class.getResourceAsStream("/audit-log/golden-vectors.json")) {
      assertThat(is).isNotNull();
      fixture = om.readTree(is);
    }
    JsonNode rowsNode = fixture.get("rows");

    // Insert each row via the Spring-injected (i.e. @Transactional-proxied) activity. Each
    // log() call opens its own transaction, acquires the advisory lock, INSERTs, and commits.
    for (int i = 0; i < rowsNode.size(); i++) {
      AuditEvent ev = om.treeToValue(rowsNode.get(i).get("event"), AuditEvent.class);
      activities.log(ev);
    }

    // Read back via admin to assert the persisted hashes match the fixture.
    try (PreparedStatement ps =
        adminConn.prepareStatement(
            "SELECT event_id, prev_hash, row_hash FROM audit_log "
                + "WHERE tenant_id = ? AND strategy_id = ? ORDER BY id ASC")) {
      ps.setString(1, "dev");
      ps.setString(2, "copytrade-v1");
      try (var rs = ps.executeQuery()) {
        int i = 0;
        byte[] priorRowHash = null;
        while (rs.next()) {
          JsonNode rowNode = rowsNode.get(i);
          String expectedRowHash = rowNode.get("expected_row_hash_hex").textValue();
          byte[] storedRowHash = rs.getBytes("row_hash");
          byte[] storedPrevHash = rs.getBytes("prev_hash");
          assertThat(storedRowHash).as("row[%d] row_hash must match golden vector", i).isNotNull();
          assertThat(AuditLogChainWriter.hex(storedRowHash))
              .as("row[%d] row_hash hex", i)
              .isEqualTo(expectedRowHash);

          // Chain link: prev_hash is NULL at the head, otherwise equals prior row's row_hash.
          if (i == 0) {
            assertThat(storedPrevHash).as("row[0] prev_hash must be SQL NULL").isNull();
          } else {
            assertThat(storedPrevHash)
                .as("row[%d] prev_hash must chain from row[%d]", i, i - 1)
                .isEqualTo(priorRowHash);
          }
          priorRowHash = storedRowHash;
          i++;
        }
        assertThat(i)
            .as("expected all %d golden rows persisted", rowsNode.size())
            .isEqualTo(rowsNode.size());
      }
    }
  }

  @Test
  @Order(2)
  void runtimeRoleStillCannotUpdateOrDeleteAfterChainPopulated() throws Exception {
    // Insert a single fresh event so the row exists for the UPDATE/DELETE assertions below.
    JsonNode fixture;
    try (InputStream is =
        AuditLogChainWriterIT.class.getResourceAsStream("/audit-log/golden-vectors.json")) {
      fixture = om.readTree(is);
    }
    AuditEvent ev = om.treeToValue(fixture.get("rows").get(0).get("event"), AuditEvent.class);
    UUID freshEventId = UUID.randomUUID();
    ev.setEventId(freshEventId.toString());
    ev.setOccurredAt(java.time.OffsetDateTime.now());
    activities.log(ev);

    long id;
    try (PreparedStatement ps =
        adminConn.prepareStatement("SELECT id FROM audit_log WHERE event_id = ?")) {
      ps.setObject(1, freshEventId);
      try (var rs = ps.executeQuery()) {
        rs.next();
        id = rs.getLong(1);
      }
    }

    // The chain row exists with prev_hash/row_hash populated. The V3 REVOKE must still bind —
    // hash population doesn't loosen the grant posture. Use a dedicated runtime connection so
    // the failed UPDATE/DELETE attempts don't poison Spring's pooled connection state.
    try (Connection runtimeConn =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), "orchestrator_runtime", RUNTIME_PASSWORD)) {
      runtimeConn.setAutoCommit(false);
      final long rowId = id;
      assertThatThrownBy(
              () -> {
                try (Statement st = runtimeConn.createStatement()) {
                  st.executeUpdate("UPDATE audit_log SET kind = 'tampered' WHERE id = " + rowId);
                }
              })
          .isInstanceOf(PSQLException.class)
          .satisfies(e -> assertThat(((PSQLException) e).getSQLState()).isEqualTo("42501"));
      runtimeConn.rollback();

      assertThatThrownBy(
              () -> {
                try (Statement st = runtimeConn.createStatement()) {
                  st.executeUpdate("DELETE FROM audit_log WHERE id = " + rowId);
                }
              })
          .isInstanceOf(PSQLException.class)
          .satisfies(e -> assertThat(((PSQLException) e).getSQLState()).isEqualTo("42501"));
      runtimeConn.rollback();
    }
  }

  /**
   * Issue #119 item 1: advisory-lock concurrency. Drive {@code N} threads each calling {@code
   * activities.log(...)} for {@code M} events on the same {@code (tenant, strategy)} chain. The
   * production {@code pg_advisory_xact_lock(hashtext(tenant)::int4, hashtext(strategy)::int4)} MUST
   * serialize the inserts; assert: (a) total row count = N*M (no insert lost), (b) every {@code
   * row_hash} is unique (no duplicate insert escaped the lock), (c) reading rows ordered by {@code
   * id ASC}, each row's {@code prev_hash} equals the prior row's {@code row_hash}, with row 0
   * having SQL NULL {@code prev_hash}.
   *
   * <p>N=4, M=15 → 60 rows. N>2 because two threads is the minimum to exhibit contention; four
   * gives every thread a high probability of overlapping with at least one other. M=15 is large
   * enough that without the lock at least one chain break would be statistically certain — a
   * sequential lock-free read-then-write across 60 INSERTs would race on the {@code ORDER BY id
   * DESC LIMIT 1} read and produce duplicate {@code prev_hash} pointers within seconds.
   */
  @Test
  @Order(3)
  void concurrentWritesProduceUnbrokenChain() throws Exception {
    final int threadCount = 4;
    final int eventsPerThread = 15;
    final int total = threadCount * eventsPerThread;

    ExecutorService pool = Executors.newFixedThreadPool(threadCount);
    try {
      CompletableFuture<?>[] futures = new CompletableFuture<?>[threadCount];
      for (int t = 0; t < threadCount; t++) {
        final int threadIdx = t;
        futures[t] =
            CompletableFuture.runAsync(
                () -> {
                  for (int m = 0; m < eventsPerThread; m++) {
                    AuditEvent ev =
                        newEvent(
                            "ConcurrentTestEvent",
                            "thread-" + threadIdx,
                            "wf-concurrent-" + threadIdx,
                            "corr-concurrent-" + threadIdx + "-" + m,
                            java.util.Map.of("thread", threadIdx, "seq", m));
                    activities.log(ev);
                  }
                },
                pool);
      }
      CompletableFuture.allOf(futures).get(60, TimeUnit.SECONDS);
    } finally {
      pool.shutdown();
      if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
        pool.shutdownNow();
      }
    }

    // Assert (a): total row count = N*M.
    try (PreparedStatement ps =
            adminConn.prepareStatement(
                "SELECT COUNT(*) FROM audit_log WHERE tenant_id = 'dev' AND strategy_id = 'copytrade-v1'");
        var rs = ps.executeQuery()) {
      rs.next();
      assertThat(rs.getLong(1))
          .as("all %d concurrent log() calls must have committed exactly one row each", total)
          .isEqualTo(total);
    }

    // Read every (prev_hash, row_hash) ordered by id ASC; assert (b) uniqueness of row_hash and
    // (c) unbroken chain linkage from row 0 (NULL prev_hash) onward.
    Set<String> seenRowHashes = new HashSet<>(total);
    byte[] expectedPrevHash = null;
    try (PreparedStatement ps =
            adminConn.prepareStatement(
                "SELECT prev_hash, row_hash FROM audit_log "
                    + "WHERE tenant_id = 'dev' AND strategy_id = 'copytrade-v1' "
                    + "ORDER BY id ASC");
        var rs = ps.executeQuery()) {
      int i = 0;
      while (rs.next()) {
        byte[] prevHash = rs.getBytes("prev_hash");
        byte[] rowHash = rs.getBytes("row_hash");
        assertThat(rowHash).as("row[%d] row_hash must be populated", i).isNotNull();
        String rowHashHex = AuditLogChainWriter.hex(rowHash);
        // (b) uniqueness — a duplicate row_hash means two threads computed the same hash from the
        // same prior chain head, which means the advisory lock failed to serialize them.
        assertThat(seenRowHashes.add(rowHashHex))
            .as("row[%d] row_hash %s must be unique across the chain", i, rowHashHex)
            .isTrue();
        // (c) chain linkage
        if (i == 0) {
          assertThat(prevHash).as("row 0 must have SQL NULL prev_hash").isNull();
        } else {
          assertThat(prevHash)
              .as("row[%d] prev_hash must equal row[%d] row_hash", i, i - 1)
              .isEqualTo(expectedPrevHash);
        }
        expectedPrevHash = rowHash;
        i++;
      }
      assertThat(i).as("must have read all %d rows", total).isEqualTo(total);
    }
  }

  /**
   * Issue #119 item 2: disabled-flag IT coverage. The unit test {@code
   * AuditActivitiesImplTest#logBypassesChainWriterAndInsertsNullHashesWhenDisabled} pins this
   * against a mocked DSLContext, but the integration path (real Postgres, real {@code
   * orchestrator_runtime} role, V3 REVOKE binding) is only exercised with the flag on. This test
   * constructs a second {@code AuditActivitiesImpl} with {@code chainWriterEnabled=false} using the
   * same Spring-managed {@code DSLContext}/{@code ObjectMapper}/{@code AuditLogChainWriter} beans,
   * logs one event, and asserts the persisted row has SQL NULL {@code prev_hash} AND SQL NULL
   * {@code row_hash}.
   *
   * <p>The second instance is constructed via {@code new} rather than @-injected because there's
   * exactly one {@code @Component AuditActivitiesImpl} bean wired by Spring (with the flag on); the
   * disabled-flag shape needs a parallel instance for the duration of this test only. The disabled
   * path does NOT acquire the advisory lock (the lock acquisition lives inside the {@code if
   * (chainWriterEnabled)} block), so {@code @Transactional} wrapping isn't a correctness
   * requirement for this path — the V3 REVOKE binding is what we're asserting still holds.
   */
  @Test
  @Order(4)
  void disabledFlagWritesNullHashes() throws Exception {
    AuditActivitiesImpl disabledActivities =
        new AuditActivitiesImpl(
            dsl, om, chainWriter, /* chainWriterEnabled= */ false, noopEventPublisher());

    AuditEvent ev =
        newEvent(
            "DisabledFlagTestEvent",
            "operator:ridopark",
            "wf-disabled",
            "corr-disabled",
            java.util.Map.of("disabled", true));
    UUID eventId = UUID.fromString(ev.getEventId());

    disabledActivities.log(ev);

    // Read back via admin. Both hash columns must be SQL NULL on the disabled path.
    try (PreparedStatement ps =
        adminConn.prepareStatement(
            "SELECT prev_hash, row_hash FROM audit_log WHERE event_id = ?")) {
      ps.setObject(1, eventId);
      try (var rs = ps.executeQuery()) {
        assertThat(rs.next()).as("disabled-flag event must have been persisted").isTrue();
        assertThat(rs.getBytes("prev_hash"))
            .as("disabled-flag row must have SQL NULL prev_hash")
            .isNull();
        assertThat(rs.getBytes("row_hash"))
            .as("disabled-flag row must have SQL NULL row_hash")
            .isNull();
      }
    }
  }

  /**
   * Issue #120: explicit single-threaded assertion that the {@code pg_advisory_xact_lock} acquired
   * by {@code log()} auto-releases at transaction commit. PR #160 (issue #119) proved this
   * implicitly via 4-thread / 60-row contention — if the lock did not auto-release, that test would
   * deadlock within seconds. Issue #120 asks for the same property expressed as a deterministic,
   * single-threaded "call log() twice in sequence; both must complete in well under the lock
   * timeout" assertion so the regression surface is visible without relying on the concurrency
   * stress test to detect it.
   *
   * <p>If a future change removes {@code @Transactional} from {@code AuditActivitiesImpl.log()},
   * the advisory lock would have no transaction to attach to (so no commit boundary to release at),
   * and the second sequential call on the same {@code (tenant, strategy)} key tuple within the same
   * pooled connection would block on its own un-released lock. The 5-second budget below is a
   * generous liveness floor — uncontended advisory-lock acquisition is sub-millisecond in practice
   * — so even a 5-second budget exposes a hang without flaking under normal CI load.
   *
   * <p>Asserts (a) both inserts persisted (two rows visible via admin read-back), (b) the second
   * row's {@code prev_hash} equals the first row's {@code row_hash} (chain link proves
   * serialization across the two sequential calls), and (c) elapsed wall-clock across the two calls
   * is below the liveness budget.
   */
  @Test
  @Order(5)
  void advisoryLockAutoReleasesAcrossSequentialLogCalls() throws Exception {
    AuditEvent first =
        newEvent(
            "SequentialLockReleaseTestEvent",
            "operator:ridopark",
            "wf-sequential-1",
            "corr-sequential-1",
            java.util.Map.of("seq", 1));
    AuditEvent second =
        newEvent(
            "SequentialLockReleaseTestEvent",
            "operator:ridopark",
            "wf-sequential-2",
            "corr-sequential-2",
            java.util.Map.of("seq", 2));

    long startNanos = System.nanoTime();
    activities.log(first);
    activities.log(second);
    long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

    // (c) Liveness floor — uncontended advisory-lock acquisition is sub-millisecond. A 5-second
    // bound is generous enough to absorb normal CI jitter but small enough that a hang (e.g.
    // un-released lock from a missing @Transactional boundary) is visible as a test failure
    // rather than as a 60-second JUnit timeout.
    assertThat(elapsedMillis)
        .as(
            "two sequential log() calls must complete inside the liveness budget;"
                + " elapsed=%dms — if this fails, the advisory lock probably did not auto-release"
                + " (check @Transactional on AuditActivitiesImpl.log())",
            elapsedMillis)
        .isLessThan(5_000L);

    // (a) + (b): read back both rows ordered by id ASC and assert chain linkage.
    try (PreparedStatement ps =
        adminConn.prepareStatement(
            "SELECT event_id, prev_hash, row_hash FROM audit_log "
                + "WHERE tenant_id = ? AND strategy_id = ? ORDER BY id ASC")) {
      ps.setString(1, "dev");
      ps.setString(2, "copytrade-v1");
      try (var rs = ps.executeQuery()) {
        assertThat(rs.next()).as("first sequential row must persist").isTrue();
        byte[] firstRowHash = rs.getBytes("row_hash");
        byte[] firstPrevHash = rs.getBytes("prev_hash");
        assertThat(firstRowHash).as("first row must have populated row_hash").isNotNull();
        assertThat(firstPrevHash)
            .as("first row must have SQL NULL prev_hash (chain head)")
            .isNull();

        assertThat(rs.next()).as("second sequential row must persist").isTrue();
        byte[] secondRowHash = rs.getBytes("row_hash");
        byte[] secondPrevHash = rs.getBytes("prev_hash");
        assertThat(secondRowHash).as("second row must have populated row_hash").isNotNull();
        // (b) Chain link: prev_hash of row 2 must equal row_hash of row 1. This proves the second
        // call's chain-writer read the first row from the chain — i.e. the first transaction
        // committed and released the advisory lock before the second call entered the lock.
        assertThat(secondPrevHash)
            .as("second row prev_hash must equal first row row_hash (chain link)")
            .isEqualTo(firstRowHash);

        assertThat(rs.next()).as("only two sequential rows must persist").isFalse();
      }
    }
  }

  /**
   * Issue #1029 Step 2: the literal 111-char production position workflow id round-trips through
   * both {@code workflow_id} and {@code correlation_id} columns after V12 widens {@code
   * correlation_id} to {@code VARCHAR(256)}, and the production after-commit funnel posts exactly
   * one Discord embed for it.
   *
   * <p><b>RED argument without V12:</b> before this migration {@code audit_log.correlation_id} was
   * {@code VARCHAR(96)}; inserting a 111-char value causes Postgres to throw {@code value too long
   * for type character varying(96)}, {@code AuditActivitiesImpl.persist()} catches and swallows it
   * (best-effort log path), the row is never committed, the {@code @TransactionalEventListener
   * AFTER_COMMIT} listener never fires, and the read-back below finds no row — the floor-breach
   * page is silently lost. After V12 widens the column to {@code VARCHAR(256)}, the INSERT
   * succeeds, the after-commit listener fires, and both assertions below pass. CI confirms this
   * with {@code RUN_DB_ITS=true} against the Flyway-migrated schema; the schema-parity test in
   * {@code services/audit} is the local RED carrier (Docker-free).
   *
   * <p>The 111-char id is the production {@code staging_paper/copytrade-v1} position workflow id
   * observed in 72 failed floor-breach audit INSERTs in the 24 h before issue #1029 was filed:
   * {@code t-staging_paper/s-copytrade-v1/pos/SMCI
   * 261120C00050000/chat-messages-769797179992571914-1541869380974157876:0}.
   */
  @Test
  @Order(6)
  void floorBreachAlertedWithLongIdInsertsPagesViaRealFunnel() throws Exception {
    // The literal 111-char production position workflow id from staging_paper/copytrade-v1 that
    // caused 72 silent floor-breach misses in the 24 h before #1029 was filed.
    final String PROD_WORKFLOW_ID =
        "t-staging_paper/s-copytrade-v1/pos/SMCI  261120C00050000"
            + "/chat-messages-769797179992571914-1541869380974157876:0";
    assertThat(PROD_WORKFLOW_ID).as("production id must be 111 chars").hasSize(111);

    AuditEvent ev = new AuditEvent();
    ev.setSchemaVersion(1L);
    ev.setTenantId("staging_paper");
    ev.setStrategyId("copytrade-v1");
    ev.setEventId(UUID.randomUUID().toString());
    ev.setOccurredAt(java.time.OffsetDateTime.now());
    ev.setKind("FloorBreachAlerted");
    ev.setActor("FloorBreachAlertLoop");
    ev.setWorkflowId(PROD_WORKFLOW_ID);
    ev.setCorrelationId(PROD_WORKFLOW_ID);
    ev.setSubject(
        java.util.Map.of(
            "contract_symbol", "SMCI  261120C00050000",
            "qty", "10",
            "entry_premium", "1.50",
            "current_bid", "0.75",
            "loss_pct", "0.50",
            "step", "1",
            "threshold", "0.50"));

    UUID eventId = UUID.fromString(ev.getEventId());

    // Drive the event through the Spring-proxied AuditActivitiesImpl so the @Transactional
    // boundary is exercised and the @TransactionalEventListener(AFTER_COMMIT) on
    // OrderFailureAlerter fires after the INSERT commits.
    activities.log(ev);

    // --- Row assertion: the 111-char ids must both have been stored intact with a chain hash. ---
    try (PreparedStatement ps =
        adminConn.prepareStatement(
            "SELECT length(workflow_id) AS wf_len, length(correlation_id) AS corr_len, "
                + "row_hash IS NOT NULL AS has_hash "
                + "FROM audit_log WHERE event_id = ?")) {
      ps.setObject(1, eventId);
      try (var rs = ps.executeQuery()) {
        assertThat(rs.next())
            .as(
                "FloorBreachAlerted row must be committed to audit_log (without V12 the"
                    + " INSERT fails with 'value too long for type character varying(96)')")
            .isTrue();
        assertThat(rs.getInt("wf_len"))
            .as("workflow_id column must store the full 111-char id")
            .isEqualTo(111);
        assertThat(rs.getInt("corr_len"))
            .as(
                "correlation_id column must store the full 111-char id"
                    + " after V12 widens it to VARCHAR(256)")
            .isEqualTo(111);
        assertThat(rs.getBoolean("has_hash"))
            .as("row_hash must be non-null (chain writer is enabled)")
            .isTrue();
      }
    }

    // --- Funnel assertion: AFTER_COMMIT listener must have posted exactly one Discord embed. ---
    ArgumentCaptor<WebhookEmbed> embedCaptor = ArgumentCaptor.forClass(WebhookEmbed.class);
    verify(webhookClient, times(1)).postEmbedToUrl(any(), embedCaptor.capture());
    WebhookEmbed embed = embedCaptor.getValue();
    assertThat(embed.title())
        .as("floor-breach embed title must start with ':rotating_light: FLOOR BREACH'")
        .startsWith(":rotating_light: FLOOR BREACH");
    assertThat(embed.footer())
        .as("embed footer must contain the full 111-char workflow id")
        .contains(PROD_WORKFLOW_ID);
  }

  /**
   * Build a fresh AuditEvent on the (tenant=dev, strategy=copytrade-v1) chain. Each call mints a
   * new UUIDv4 event_id and stamps occurred_at=now() so callers don't collide on the event_id
   * UNIQUE constraint or on @{@code id ASC} ordering even when called from concurrent threads.
   */
  private static AuditEvent newEvent(
      String kind,
      String actor,
      String workflowId,
      String correlationId,
      java.util.Map<String, Object> subject) {
    AuditEvent ev = new AuditEvent();
    ev.setSchemaVersion(1L);
    ev.setTenantId("dev");
    ev.setStrategyId("copytrade-v1");
    ev.setEventId(UUID.randomUUID().toString());
    ev.setOccurredAt(java.time.OffsetDateTime.now());
    ev.setKind(kind);
    ev.setActor(actor);
    ev.setWorkflowId(workflowId);
    ev.setCorrelationId(correlationId);
    ev.setSubject(subject);
    return ev;
  }

  /**
   * Issue #302: no-op event publisher so this IT exercises only the audit-write path (no
   * OrderFailureAlerter listener is wired in this narrow context, so the published
   * AuditEventCommitted is simply dropped).
   */
  private static org.springframework.context.ApplicationEventPublisher noopEventPublisher() {
    return event -> {};
  }
}
