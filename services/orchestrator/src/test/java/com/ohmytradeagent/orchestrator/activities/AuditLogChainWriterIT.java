package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ohmytradeagent.contract.AuditEvent;
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
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
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

    @Bean
    @Primary
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
     */
    @Bean
    AuditActivitiesImpl auditActivities(
        org.jooq.DSLContext dsl,
        ObjectMapper objectMapper,
        AuditLogChainWriter chainWriter,
        @org.springframework.beans.factory.annotation.Value("${audit.chain-writer.enabled:true}")
            boolean chainWriterEnabled) {
      return new AuditActivitiesImpl(dsl, objectMapper, chainWriter, chainWriterEnabled);
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

  @BeforeAll
  static void openAdminConnection() throws Exception {
    adminConn =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  @AfterAll
  static void closeAdminConnection() throws Exception {
    if (adminConn != null) adminConn.close();
    if (postgres != null) postgres.stop();
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
                    AuditEvent ev = new AuditEvent();
                    ev.setSchemaVersion(1L);
                    ev.setTenantId("dev");
                    ev.setStrategyId("copytrade-v1");
                    // UUIDv4 — globally unique so the event_id UNIQUE constraint is satisfied
                    // regardless of which thread inserts which event.
                    ev.setEventId(UUID.randomUUID().toString());
                    ev.setOccurredAt(java.time.OffsetDateTime.now());
                    ev.setKind("ConcurrentTestEvent");
                    ev.setActor("thread-" + threadIdx);
                    ev.setWorkflowId("wf-concurrent-" + threadIdx);
                    ev.setCorrelationId("corr-concurrent-" + threadIdx + "-" + m);
                    ev.setSubject(java.util.Map.of("thread", threadIdx, "seq", m));
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
        new AuditActivitiesImpl(dsl, om, chainWriter, /* chainWriterEnabled= */ false);

    AuditEvent ev = new AuditEvent();
    ev.setSchemaVersion(1L);
    ev.setTenantId("dev");
    ev.setStrategyId("copytrade-v1");
    UUID eventId = UUID.randomUUID();
    ev.setEventId(eventId.toString());
    ev.setOccurredAt(java.time.OffsetDateTime.now());
    ev.setKind("DisabledFlagTestEvent");
    ev.setActor("operator:ridopark");
    ev.setWorkflowId("wf-disabled");
    ev.setCorrelationId("corr-disabled");
    ev.setSubject(java.util.Map.of("disabled", true));

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
}
