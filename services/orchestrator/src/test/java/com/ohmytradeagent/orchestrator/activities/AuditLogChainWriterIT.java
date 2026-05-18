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
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.util.PSQLException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Issue #85: Testcontainers IT for {@link AuditActivitiesImpl}'s hash-chain INSERT path.
 *
 * <ul>
 *   <li>Connects as the {@code orchestrator_runtime} role (V4) so the V3 REVOKE binding is
 *       exercised end-to-end.
 *   <li>Inserts the golden-vector rows in order via the production writer and asserts the persisted
 *       {@code row_hash} matches the fixture.
 *   <li>Asserts {@code UPDATE} / {@code DELETE} on the inserted rows raises {@code PSQLException}
 *       with {@code SQLSTATE = 42501} — the V3 REVOKE still binds even with hash columns populated.
 * </ul>
 *
 * <p>Gated on {@code RUN_DB_ITS=true} to match {@code OrchestratorRuntimeRoleIT} convention —
 * Testcontainers requires Docker, which CI runners may not provide.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class AuditLogChainWriterIT {

  // Postgres 16 matches the homelab image, OrchestratorRuntimeRoleIT, and DailyPnlActivitiesImplIT.
  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  private static final String RUNTIME_PASSWORD = "it-test-pw";

  private static Connection adminConn;
  private static Connection runtimeConn;
  private static AuditActivitiesImpl activities;
  private static ObjectMapper om;

  @BeforeAll
  static void initDb() throws Exception {
    // Run V1..V4 as the container's default superuser.
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();

    adminConn =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    try (Statement st = adminConn.createStatement()) {
      st.execute("ALTER ROLE orchestrator_runtime PASSWORD '" + RUNTIME_PASSWORD + "'");
    }

    // Connect as orchestrator_runtime — this is the actual production code path.
    runtimeConn =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), "orchestrator_runtime", RUNTIME_PASSWORD);
    // Auto-commit OFF so @Transactional semantics are observable: the FOR UPDATE on the prior
    // chain head must hold the lock until the INSERT commits. We commit explicitly after each
    // event below.
    runtimeConn.setAutoCommit(false);

    om = new ObjectMapper().registerModule(new JavaTimeModule());
    DSLContext dsl = DSL.using(runtimeConn, SQLDialect.POSTGRES);
    AuditLogChainWriter writer = new AuditLogChainWriter(om);
    activities = new AuditActivitiesImpl(dsl, om, writer, true);
  }

  @AfterAll
  static void closeDb() throws Exception {
    if (runtimeConn != null) runtimeConn.close();
    if (adminConn != null) adminConn.close();
  }

  @Test
  void writesGoldenVectorChainAndRoundTrips() throws Exception {
    JsonNode fixture;
    try (InputStream is =
        AuditLogChainWriterIT.class.getResourceAsStream("/audit-log/golden-vectors.json")) {
      assertThat(is).isNotNull();
      fixture = om.readTree(is);
    }
    JsonNode rowsNode = fixture.get("rows");

    // Clean any prior IT residue for this chain so the test is deterministic across reruns.
    // The runtime role lacks DELETE, so we clean via the admin connection.
    try (Statement st = adminConn.createStatement()) {
      st.executeUpdate(
          "DELETE FROM audit_log WHERE tenant_id = 'dev' AND strategy_id = 'copytrade-v1'");
    }

    // Insert each row via the production writer, then commit.
    for (int i = 0; i < rowsNode.size(); i++) {
      AuditEvent ev = om.treeToValue(rowsNode.get(i).get("event"), AuditEvent.class);
      activities.log(ev);
      runtimeConn.commit();
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
  void runtimeRoleStillCannotUpdateOrDeleteAfterChainPopulated() throws Exception {
    // Load row 0 of the fixture for a known-shape event, then re-key to avoid the event_id UNIQUE
    // collision with the chain-write test above.
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
    runtimeConn.commit();

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
    // hash population doesn't loosen the grant posture.
    assertThatThrownBy(
            () -> {
              try (Statement st = runtimeConn.createStatement()) {
                st.executeUpdate("UPDATE audit_log SET kind = 'tampered' WHERE id = " + id);
              }
            })
        .isInstanceOf(PSQLException.class)
        .satisfies(e -> assertThat(((PSQLException) e).getSQLState()).isEqualTo("42501"));
    // Roll back the failed UPDATE attempt so subsequent statements run on a clean transaction.
    runtimeConn.rollback();

    assertThatThrownBy(
            () -> {
              try (Statement st = runtimeConn.createStatement()) {
                st.executeUpdate("DELETE FROM audit_log WHERE id = " + id);
              }
            })
        .isInstanceOf(PSQLException.class)
        .satisfies(e -> assertThat(((PSQLException) e).getSQLState()).isEqualTo("42501"));
    runtimeConn.rollback();
  }
}
