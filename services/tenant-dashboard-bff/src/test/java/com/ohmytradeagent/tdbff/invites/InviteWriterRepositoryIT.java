package com.ohmytradeagent.tdbff.invites;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.tdbff.invites.InviteWriterRepository.InviteRecord;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * Real-Postgres coverage for {@link InviteWriterRepository} against the V4/V5 schema, executed as
 * the least-privilege {@code dashboard_writer} role. Proves the behaviors the WebMvc slices mock
 * away:
 *
 * <ul>
 *   <li>create is idempotent per open (email, tenant) — ON CONFLICT refreshes the SAME row;
 *   <li>bind grants exactly the invite's tenant(s), consumes the invite (audit fields set), inserts
 *       the dashboard_user grant, and is a no-op on a re-bind of a consumed invite;
 *   <li>expired / wrong-email invites grant nothing; email match is case/whitespace-insensitive;
 *   <li>consume precedes insert (a consumed invite yields no grant on a subsequent bind).
 * </ul>
 *
 * Gated on {@code RUN_DB_ITS=true} like the module's other DB ITs. Testcontainers connects as the
 * superuser to run Flyway + set up fixtures; the repo itself connects as {@code dashboard_writer}.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class InviteWriterRepositoryIT {

  private static final String READONLY_PW = "readonly-test-pw";
  private static final String WRITER_PW = "writer-test-pw";

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  private static HikariDataSource writerDs;
  private static InviteWriterRepository repo;

  @BeforeAll
  static void setUp() {
    org.flywaydb.core.Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/dashboard")
        .placeholders(
            Map.of(
                "dashboard_readonly_password", READONLY_PW,
                "dashboard_writer_password", WRITER_PW))
        .load()
        .migrate();

    writerDs = new HikariDataSource();
    writerDs.setJdbcUrl(postgres.getJdbcUrl());
    writerDs.setUsername("dashboard_writer");
    writerDs.setPassword(WRITER_PW);
    writerDs.setMaximumPoolSize(2);

    DSLContext writerDsl = DSL.using(writerDs, SQLDialect.POSTGRES);
    repo = new InviteWriterRepository(writerDsl);
  }

  @AfterAll
  static void tearDown() {
    if (writerDs != null) {
      writerDs.close();
    }
  }

  @BeforeEach
  void clean() throws SQLException {
    try (Connection c = asSuperuser();
        var st = c.createStatement()) {
      st.executeUpdate("TRUNCATE dashboard_user_invite, dashboard_user");
    }
  }

  // ---- create ----

  @Test
  void createInvite_insertsOpenRow_returningNormalizedEmail() {
    InviteRecord r = repo.createInvite("  Alice@Example.COM ", "acme", "op@x.com", 7);
    assertThat(r.id()).isNotNull();
    assertThat(r.tenantId()).isEqualTo("acme");
    assertThat(r.email()).isEqualTo("alice@example.com");
    assertThat(r.expiresAt()).isNotNull();
    assertThat(openInviteCount()).isEqualTo(1);
  }

  @Test
  void createInvite_repeatWhileOpen_isIdempotent_sameRow() {
    InviteRecord first = repo.createInvite("bob@x.com", "acme", "op@x.com", 1);
    // Different case + whitespace must collide on the partial unique index (lower(email), tenant).
    InviteRecord second = repo.createInvite("  BOB@X.com", "acme", "op2@x.com", 30);

    assertThat(second.id()).isEqualTo(first.id()); // refreshed, not duplicated
    assertThat(second.expiresAt()).isAfter(first.expiresAt()); // expiry pushed out
    assertThat(openInviteCount()).isEqualTo(1);
  }

  @Test
  void createInvite_differentTenantsSameEmail_areSeparateOpenRows() {
    repo.createInvite("carol@x.com", "acme", "op@x.com", 7);
    repo.createInvite("carol@x.com", "globex", "op@x.com", 7);
    assertThat(openInviteCount()).isEqualTo(2);
  }

  // ---- bind ----

  @Test
  void bind_matchingInvite_grantsConsumesAndInsertsUser() throws SQLException {
    UUID id = repo.createInvite("dave@x.com", "acme", "op@x.com", 7).id();

    List<String> granted = repo.bindMatchingInvites("google", "sub-dave", " DAVE@x.com ");

    assertThat(granted).containsExactly("acme");
    assertThat(dashboardUserCount("google", "sub-dave", "acme")).isEqualTo(1);
    assertConsumed(id, "google", "sub-dave");
  }

  @Test
  void bind_secondBindOfConsumedInvite_grantsNothing() throws SQLException {
    repo.createInvite("erin@x.com", "acme", "op@x.com", 7);
    assertThat(repo.bindMatchingInvites("google", "sub-erin", "erin@x.com"))
        .containsExactly("acme");

    // Invite already consumed => the open-invite lookup finds nothing => empty, idempotent.
    List<String> second = repo.bindMatchingInvites("google", "sub-erin", "erin@x.com");
    assertThat(second).isEmpty();
    assertThat(dashboardUserCount("google", "sub-erin", "acme")).isEqualTo(1); // still exactly one
  }

  @Test
  void bind_expiredInvite_grantsNothing() throws SQLException {
    // An already-expired open invite (inserted directly; createInvite only makes future-dated).
    insertRawInvite("frank@x.com", "acme", "-1 day");
    List<String> granted = repo.bindMatchingInvites("google", "sub-frank", "frank@x.com");
    assertThat(granted).isEmpty();
    assertThat(dashboardUserCount("google", "sub-frank", "acme")).isEqualTo(0);
  }

  @Test
  void bind_wrongEmail_grantsNothing() throws SQLException {
    repo.createInvite("grace@x.com", "acme", "op@x.com", 7);
    List<String> granted = repo.bindMatchingInvites("google", "sub-x", "someone-else@x.com");
    assertThat(granted).isEmpty();
    assertThat(dashboardUserCount("google", "sub-x", "acme")).isEqualTo(0);
  }

  @Test
  void bind_multipleTenantsForOneEmail_allGrantedAndConsumed() {
    repo.createInvite("heidi@x.com", "acme", "op@x.com", 7);
    repo.createInvite("heidi@x.com", "globex", "op@x.com", 7);

    List<String> granted = repo.bindMatchingInvites("facebook", "sub-heidi", "HEIDI@X.com");

    assertThat(granted).containsExactlyInAnyOrder("acme", "globex");
    assertThat(openInviteCount()).isEqualTo(0); // both consumed
  }

  // ---- delete (operator tenant-delete teardown, Phase 3) ----

  @Test
  void deleteTenantIdentities_removesUsersAndInvites_scopedByTenant_returnsCounts()
      throws SQLException {
    // acme: one bound member (via bind) + one consumed invite row.
    repo.createInvite("ivan@x.com", "acme", "op@x.com", 7);
    assertThat(repo.bindMatchingInvites("google", "sub-ivan", "ivan@x.com"))
        .containsExactly("acme");
    // globex: an unrelated open invite that MUST survive an acme delete.
    repo.createInvite("judy@x.com", "globex", "op@x.com", 7);

    InviteWriterRepository.DeletedIdentityCounts counts = repo.deleteTenantIdentities("acme");

    assertThat(counts.users()).isEqualTo(1);
    assertThat(counts.invites()).isEqualTo(1);
    // acme wiped, globex untouched.
    assertThat(userCountForTenant("acme")).isZero();
    assertThat(inviteCountForTenant("acme")).isZero();
    assertThat(userCountForTenant("globex")).isZero();
    assertThat(inviteCountForTenant("globex")).isEqualTo(1);
  }

  @Test
  void deleteTenantIdentities_isIdempotent_secondCallDeletesZero() {
    repo.createInvite("ken@x.com", "acme", "op@x.com", 7);
    repo.bindMatchingInvites("google", "sub-ken", "ken@x.com");

    InviteWriterRepository.DeletedIdentityCounts first = repo.deleteTenantIdentities("acme");
    assertThat(first.users()).isEqualTo(1);
    assertThat(first.invites()).isEqualTo(1);

    // Second call (or a tenant that never had identities) deletes nothing and does not throw.
    InviteWriterRepository.DeletedIdentityCounts second = repo.deleteTenantIdentities("acme");
    assertThat(second.users()).isZero();
    assertThat(second.invites()).isZero();
  }

  @Test
  void deleteTenantIdentities_unknownTenant_isNoOpSuccess() {
    InviteWriterRepository.DeletedIdentityCounts counts =
        repo.deleteTenantIdentities("never-existed");
    assertThat(counts.users()).isZero();
    assertThat(counts.invites()).isZero();
  }

  // ---- newest-created-at (residual-cleanup incarnation guard, Phase 2) ----

  @Test
  void newestDashboardRowCreatedAt_noRows_returnsNull() {
    assertThat(repo.newestDashboardRowCreatedAt("never-existed")).isNull();
  }

  @Test
  void newestDashboardRowCreatedAt_returnsMaxAcrossBothTables_scopedByTenant() throws SQLException {
    // acme: an OLD invite (2026-06-01) + a NEWER bound member (2026-07-02). globex: a row that must
    // not leak into acme's max. Proves the dashboard_writer role can READ created_at (V8 grant) and
    // that the UNION-max is tenant-scoped and spans BOTH tables.
    insertRawUserAt("google", "sub-old", "acme", "2026-06-01T00:00:00Z");
    insertRawInviteAt("new@x.com", "acme", "2026-07-02T00:00:00Z");
    insertRawInviteAt("other@x.com", "globex", "2026-08-01T00:00:00Z");

    java.time.OffsetDateTime newest = repo.newestDashboardRowCreatedAt("acme");

    assertThat(newest).isNotNull();
    assertThat(newest.toInstant())
        .isEqualTo(java.time.Instant.parse("2026-07-02T00:00:00Z")); // the member, not globex
  }

  @Test
  void newestDashboardRowCreatedAt_onlyInvite_readsInviteCreatedAt() throws SQLException {
    insertRawInviteAt("solo@x.com", "acme", "2026-07-05T00:00:00Z");
    assertThat(repo.newestDashboardRowCreatedAt("acme").toInstant())
        .isEqualTo(java.time.Instant.parse("2026-07-05T00:00:00Z"));
  }

  // ---- fixtures / assertions ----

  private static Connection asSuperuser() throws SQLException {
    return DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  private int openInviteCount() {
    try (Connection c = asSuperuser();
        var rs =
            c.createStatement()
                .executeQuery(
                    "SELECT count(*) FROM dashboard_user_invite WHERE consumed_at IS NULL")) {
      rs.next();
      return rs.getInt(1);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  private int inviteCountForTenant(String tenantId) throws SQLException {
    return countForTenant("dashboard_user_invite", tenantId);
  }

  private int userCountForTenant(String tenantId) throws SQLException {
    return countForTenant("dashboard_user", tenantId);
  }

  private int countForTenant(String table, String tenantId) throws SQLException {
    // Table name is a test-controlled constant (never caller input) — safe to inline.
    try (Connection c = asSuperuser();
        var ps = c.prepareStatement("SELECT count(*) FROM " + table + " WHERE tenant_id = ?")) {
      ps.setString(1, tenantId);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private int dashboardUserCount(String provider, String subject, String tenantId)
      throws SQLException {
    try (Connection c = asSuperuser();
        var ps =
            c.prepareStatement(
                "SELECT count(*) FROM dashboard_user "
                    + "WHERE provider = ? AND subject = ? AND tenant_id = ?")) {
      ps.setString(1, provider);
      ps.setString(2, subject);
      ps.setString(3, tenantId);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private void assertConsumed(UUID id, String provider, String subject) throws SQLException {
    try (Connection c = asSuperuser();
        var ps =
            c.prepareStatement(
                "SELECT consumed_at, consumed_provider, consumed_subject "
                    + "FROM dashboard_user_invite WHERE id = ?")) {
      ps.setObject(1, id);
      try (var rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getTimestamp("consumed_at")).isNotNull();
        assertThat(rs.getString("consumed_provider")).isEqualTo(provider);
        assertThat(rs.getString("consumed_subject")).isEqualTo(subject);
      }
    }
  }

  private void insertRawUserAt(
      String provider, String subject, String tenantId, String createdAtIso) throws SQLException {
    try (Connection c = asSuperuser();
        var ps =
            c.prepareStatement(
                "INSERT INTO dashboard_user (provider, subject, tenant_id, created_at) "
                    + "VALUES (?, ?, ?, ?::timestamptz)")) {
      ps.setString(1, provider);
      ps.setString(2, subject);
      ps.setString(3, tenantId);
      ps.setString(4, createdAtIso);
      ps.executeUpdate();
    }
  }

  private void insertRawInviteAt(String email, String tenantId, String createdAtIso)
      throws SQLException {
    try (Connection c = asSuperuser();
        var ps =
            c.prepareStatement(
                "INSERT INTO dashboard_user_invite (email, tenant_id, created_by, created_at,"
                    + " expires_at) VALUES (?, ?, 'op@x.com', ?::timestamptz, now() + interval '7"
                    + " days')")) {
      ps.setString(1, email);
      ps.setString(2, tenantId);
      ps.setString(3, createdAtIso);
      ps.executeUpdate();
    }
  }

  private void insertRawInvite(String email, String tenantId, String expiryOffset)
      throws SQLException {
    try (Connection c = asSuperuser();
        var ps =
            c.prepareStatement(
                "INSERT INTO dashboard_user_invite (email, tenant_id, created_by, expires_at) "
                    + "VALUES (?, ?, 'op@x.com', now() + (? || '')::interval)")) {
      ps.setString(1, email);
      ps.setString(2, tenantId);
      ps.setString(3, expiryOffset);
      ps.executeUpdate();
    }
  }
}
