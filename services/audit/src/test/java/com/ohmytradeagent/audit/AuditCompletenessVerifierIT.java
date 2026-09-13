package com.ohmytradeagent.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ohmytradeagent.contract.AuditEvent;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Issue #90 acceptance criterion 4 verified end-to-end: the verifier reads from a real {@code
 * audit_log} table (Testcontainers Postgres, same v16 image as the homelab + the other ITs in
 * orchestrator-svc), produces a single completeness score, and exits with that score visible to the
 * CronJob log scraper.
 *
 * <p>Gated on {@code RUN_DB_ITS=true} per the project convention (orchestrator-svc's {@code
 * AuditLogChainWriterIT}, {@code DailyPnlActivitiesImplIT}, etc.) — Testcontainers requires Docker,
 * which CI runners may not provide. Local {@code mvn verify} without Docker skips this IT cleanly.
 *
 * <p>The schema is loaded by directly invoking the orchestrator's V2 audit_log migration (sibling
 * module). audit-svc is a read-only consumer and does not own these migrations; the test scenario
 * gets to write rows because it connects as the Testcontainers superuser.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class AuditCompletenessVerifierIT {

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  private static Connection conn;
  private static DSLContext dsl;
  private static AuditCompletenessVerifier verifier;
  private static final ObjectMapper OM = new ObjectMapper().registerModule(new JavaTimeModule());

  @BeforeAll
  static void initDb() throws Exception {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        // Inline migration: minimal audit_log schema mirroring orchestrator V2. Loading
        // orchestrator's migration directory directly would create a build-order coupling; the
        // schema here intentionally matches V2__audit_log.sql one-to-one and is kept in sync via
        // human review when the orchestrator migration changes.
        .locations("classpath:db/migration/audit-it")
        .load()
        .migrate();

    conn =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    dsl = DSL.using(conn, SQLDialect.POSTGRES);
    verifier =
        new AuditCompletenessVerifier(
            new JooqAuditEventSource(dsl, OM), new LedgerRederiver(), openPositions);
  }

  /**
   * Stands in for Temporal. Defaults to "nothing is open", which keeps every pre-existing assertion
   * meaning what it did: a suppressed close on a position that is NOT open is still a divergence.
   * Tests that want the open-position path add the correlation id here.
   */
  private static final Set<String> openCorrelations = new HashSet<>();

  private static final OpenPositionSource openPositions =
      (tenantId, strategyId) -> Set.copyOf(openCorrelations);

  @AfterAll
  static void closeDb() throws Exception {
    if (conn != null && !conn.isClosed()) {
      conn.close();
    }
  }

  @Test
  void cleanLifecycleInPostgresYieldsScoreOneHundred() throws Exception {
    truncate();
    String corr = "signal-clean-" + UUID.randomUUID();
    insert(audit(corr, "SignalReceived", ts(0, 0)));
    insert(audit(corr, "EntryFilled", ts(0, 1)));
    insert(audit(corr, "PositionEntered", ts(0, 2)));
    insert(audit(corr, "PositionClosed", ts(0, 3)));

    AuditCompletenessVerifier.Report report =
        verifier.verify(
            "dev", "copytrade-v1", day(LocalDate.of(2026, 5, 1)), day(LocalDate.of(2026, 5, 2)));

    assertThat(report.passed()).as("clean lifecycle must pass").isTrue();
    assertThat(report.score()).isEqualTo(100.0);
    assertThat(report.totalLifecycles()).isEqualTo(1);
    assertThat(report.completeLifecycles()).isEqualTo(1);
    assertThat(report.divergences()).isEmpty();
  }

  @Test
  void suppressedTerminalInPostgresYieldsLessThanOneHundred() throws Exception {
    truncate();
    String corr = "signal-broken-" + UUID.randomUUID();
    insert(audit(corr, "EntryFilled", ts(0, 0)));
    insert(audit(corr, "PositionEntered", ts(0, 1)));
    // PositionClosed omitted.

    AuditCompletenessVerifier.Report report =
        verifier.verify(
            "dev", "copytrade-v1", day(LocalDate.of(2026, 5, 1)), day(LocalDate.of(2026, 5, 2)));

    assertThat(report.passed()).isFalse();
    assertThat(report.score()).isLessThan(100.0);
    assertThat(report.totalLifecycles()).isEqualTo(1);
    assertThat(report.completeLifecycles()).isEqualTo(0);
    assertThat(report.divergences())
        .hasSize(1)
        .allSatisfy(d -> assertThat(d.kind()).isEqualTo(Divergence.Kind.MISSING_TERMINAL_CLOSE));
    assertThat(report.openLifecycles()).isZero();
  }

  // #853: the same suppressed close, except the position IS still open. This is the overnight hold
  // that made the verifier permanently red on every real tenant: it must now carry forward as OPEN,
  // score 100% over zero settled lifecycles, and raise no divergence.
  @Test
  void unclosedLifecycleWhosePositionIsStillOpenIsCarriedForward() throws Exception {
    truncate();
    String corr = "signal-open-" + UUID.randomUUID();
    insert(audit(corr, "EntryFilled", ts(0, 0)));
    insert(audit(corr, "PositionEntered", ts(0, 1)));
    // PositionClosed absent because the position is STILL OPEN, not because an event was lost.
    openCorrelations.add(corr);
    try {
      AuditCompletenessVerifier.Report report =
          verifier.verify(
              "dev", "copytrade-v1", day(LocalDate.of(2026, 5, 1)), day(LocalDate.of(2026, 5, 2)));

      assertThat(report.divergences()).isEmpty();
      assertThat(report.openLifecycles()).isEqualTo(1);
      assertThat(report.totalLifecycles()).as("settled lifecycles only").isZero();
      assertThat(report.score()).isEqualTo(100.0);
      assertThat(report.passed()).isTrue();
    } finally {
      openCorrelations.remove(corr);
    }
  }

  // #853: the real SQL behind pair discovery. The unit tests stub AuditPairSource, so this is the
  // only place the jOOQ column mapping is exercised at all — a typo in a column name would
  // otherwise
  // surface first in the nightly CronJob.
  @Test
  void pairsInWindowDiscoversEveryTenantStrategyWithActivity() throws Exception {
    truncate();
    insert(audit("corr-a", "EntryFilled", ts(0, 0)));
    insert(auditFor("other_tenant", "watchlist-trigger-v1", "corr-b", "EntryFilled", ts(0, 1)));
    insert(auditFor("other_tenant", "watchlist-trigger-v1", "corr-c", "PositionClosed", ts(0, 2)));

    List<AuditPairSource.TenantStrategy> pairs =
        new JooqAuditEventSource(dsl, OM)
            .pairsInWindow(day(LocalDate.of(2026, 5, 1)), day(LocalDate.of(2026, 5, 2)));

    assertThat(pairs)
        .as("one entry per distinct (tenant, strategy), ordered, duplicates collapsed")
        .containsExactly(
            new AuditPairSource.TenantStrategy("dev", "copytrade-v1"),
            new AuditPairSource.TenantStrategy("other_tenant", "watchlist-trigger-v1"));
  }

  @Test
  void pairsInWindowExcludesActivityOutsideTheWindow() throws Exception {
    truncate();
    insert(audit("corr-in", "EntryFilled", ts(0, 0)));
    // ts() is an hour-of-DAY offset from 14:00 on 2026-05-01, not a duration — ts(48,0) asked for
    // hour 62. Use an explicit later day instead, which is what "outside the window" meant.
    insert(
        auditFor(
            "late_tenant",
            "copytrade-v1",
            "corr-late",
            "EntryFilled",
            day(LocalDate.of(2026, 5, 3))));

    List<AuditPairSource.TenantStrategy> pairs =
        new JooqAuditEventSource(dsl, OM)
            .pairsInWindow(day(LocalDate.of(2026, 5, 1)), day(LocalDate.of(2026, 5, 2)));

    assertThat(pairs).containsExactly(new AuditPairSource.TenantStrategy("dev", "copytrade-v1"));
  }

  @Test
  void emptyWindowReturnsOneHundredPercent() throws Exception {
    // "Market closed today, no activity" must not break the 20-consecutive-green-days streak.
    truncate();
    AuditCompletenessVerifier.Report report =
        verifier.verify(
            "dev",
            "copytrade-v1",
            day(LocalDate.of(2026, 12, 25)),
            day(LocalDate.of(2026, 12, 26)));

    assertThat(report.passed()).isTrue();
    assertThat(report.score()).isEqualTo(100.0);
    assertThat(report.totalEvents()).isZero();
    assertThat(report.totalLifecycles()).isZero();
  }

  // ---- helpers ----

  private static void truncate() throws Exception {
    try (Statement s = conn.createStatement()) {
      s.execute("TRUNCATE TABLE audit_log");
    }
  }

  private static AuditEvent audit(String corr, String kind, OffsetDateTime occurred) {
    return auditFor("dev", "copytrade-v1", corr, kind, occurred);
  }

  /** Same fixture for an arbitrary (tenant, strategy) — pair-discovery needs more than one. */
  private static AuditEvent auditFor(
      String tenantId, String strategyId, String corr, String kind, OffsetDateTime occurred) {
    AuditEvent e = new AuditEvent();
    e.setSchemaVersion(1L);
    e.setTenantId(tenantId);
    e.setStrategyId(strategyId);
    e.setEventId(UUID.randomUUID().toString());
    e.setOccurredAt(occurred);
    e.setKind(kind);
    e.setSubject(Map.of("note", "IT fixture"));
    e.setActor("workflow:test");
    e.setWorkflowId("wf-test");
    e.setCorrelationId(corr);
    return e;
  }

  private static void insert(AuditEvent e) throws Exception {
    dsl.execute(
        "INSERT INTO audit_log (schema_version, tenant_id, strategy_id, event_id, occurred_at, "
            + "kind, actor, workflow_id, correlation_id, subject) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
        e.getSchemaVersion().intValue(),
        e.getTenantId(),
        e.getStrategyId(),
        UUID.fromString(e.getEventId()),
        java.sql.Timestamp.from(e.getOccurredAt().toInstant()),
        e.getKind(),
        e.getActor(),
        e.getWorkflowId(),
        e.getCorrelationId(),
        OM.writeValueAsString(e.getSubject()));
  }

  private static OffsetDateTime ts(int hourOffset, int minuteOffset) {
    return OffsetDateTime.of(2026, 5, 1, 14 + hourOffset, minuteOffset, 0, 0, ZoneOffset.UTC);
  }

  private static OffsetDateTime day(LocalDate d) {
    return d.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
  }
}
