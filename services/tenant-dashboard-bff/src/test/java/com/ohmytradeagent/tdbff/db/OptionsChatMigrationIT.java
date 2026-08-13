package com.ohmytradeagent.tdbff.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real-Postgres coverage for V9 (the {@code /options-chat} mirror store). The structural lock in
 * {@link DashboardMigrationSqlStructureTest} asserts the SQL we WILL apply; this asserts what
 * Postgres actually permits, which is the only way to catch the two privilege traps V9 is shaped
 * around — and both fail at RUNTIME, in production, if they are wrong:
 *
 * <ul>
 *   <li><b>The ON CONFLICT / WHERE conflict probe needs SELECT.</b> A writer holding only INSERT
 *       cannot run {@code INSERT ... ON CONFLICT (message_id) DO NOTHING} — the arbiter probe reads
 *       the index and Postgres raises 42501. That exact denial is why the invite bind carries a
 *       SAVEPOINT workaround; V9 grants SELECT so the plain statement works.
 *   <li><b>IDENTITY vs BIGSERIAL.</b> A BIGSERIAL child key would additionally need USAGE on its
 *       backing sequence, a grant no other migration here models. V9 uses GENERATED ALWAYS AS
 *       IDENTITY so the table INSERT grant suffices — proven by actually inserting as the writer.
 * </ul>
 *
 * Gated on {@code RUN_DB_ITS=true} like the module's other DB ITs (CI sets it; a Docker-less
 * workstation skips cleanly), and runs under {@code verify} via failsafe.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class OptionsChatMigrationIT {

  private static final String READONLY_PW = "readonly-test-pw";
  private static final String WRITER_PW = "writer-test-pw";
  private static final String INSUFFICIENT_PRIVILEGE = "42501";

  private static final long CHANNEL = 786109983065505792L;
  private static final long MSG = 1273987654321098765L;

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/dashboard")
        .placeholders(
            Map.of(
                "dashboard_readonly_password", READONLY_PW,
                "dashboard_writer_password", WRITER_PW))
        .load()
        .migrate();
  }

  private static Connection asSuperuser() throws SQLException {
    return DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  private static Connection asRole(String role, String password) throws SQLException {
    return DriverManager.getConnection(postgres.getJdbcUrl(), role, password);
  }

  @Test
  void tablesAndTheCursorIndexExist() throws SQLException {
    try (Connection c = asSuperuser();
        var st = c.createStatement()) {
      for (String t :
          new String[] {"options_chat_message", "options_chat_attachment", "options_chat_embed"}) {
        try (var rs = st.executeQuery("SELECT to_regclass('public." + t + "') IS NOT NULL")) {
          rs.next();
          assertThat(rs.getBoolean(1)).as("%s table", t).isTrue();
        }
      }
      assertThat(indexExists(c, "options_chat_message_channel_id_message_id_idx"))
          .as("the named index backing the read cursor")
          .isTrue();
    }
  }

  @Test
  void writerCanUpsertIdempotentlyWithOnConflict_provingTheConflictProbeSelectGrant()
      throws SQLException {
    try (Connection w = asRole("dashboard_writer", WRITER_PW);
        var st = w.createStatement()) {
      assertThat(insertMessage(st, MSG, "first")).isEqualTo(1);
      // The replay path: the scraper re-sends whatever Discord still has rendered after a restart.
      // Without SELECT on the arbiter column this line raises 42501 instead of returning 0.
      assertThat(insertMessage(st, MSG, "first")).as("replay is a silent no-op").isEqualTo(0);

      try (var rs = st.executeQuery("SELECT count(*) FROM options_chat_message")) {
        rs.next();
        assertThat(rs.getInt(1)).isEqualTo(1);
      }
    }
  }

  @Test
  void writerCanInsertChildRows_provingIdentityNeedsNoSequenceGrant() throws SQLException {
    long msg = MSG + 1;
    try (Connection w = asRole("dashboard_writer", WRITER_PW);
        var st = w.createStatement()) {
      insertMessage(st, msg, "with children");

      // A BIGSERIAL id here would need USAGE on its sequence and would fail with 42501.
      assertThat(
              st.executeUpdate(
                  "INSERT INTO options_chat_attachment (message_id, ordinal, kind, source_url) "
                      + "VALUES ("
                      + msg
                      + ", 0, 'image', 'https://cdn.discordapp.com/a.png')"))
          .isEqualTo(1);
      assertThat(
              st.executeUpdate(
                  "INSERT INTO options_chat_embed (message_id, ordinal, title) "
                      + "VALUES ("
                      + msg
                      + ", 0, 'a title')"))
          .isEqualTo(1);

      // Phase 4 fills in the fetched bytes; the UPDATE grant covers it.
      assertThat(
              st.executeUpdate(
                  "UPDATE options_chat_attachment SET fetch_state = 'ok', content_type = "
                      + "'image/webp' WHERE message_id = "
                      + msg))
          .isEqualTo(1);
    }
  }

  @Test
  void writerCanRunTheEditDetectingUpdate_provingThePredicateSelectGrant() throws SQLException {
    long msg = MSG + 2;
    try (Connection w = asRole("dashboard_writer", WRITER_PW);
        var st = w.createStatement()) {
      insertMessage(st, msg, "before edit");

      // Phase 6's reconcile. The WHERE reads the stored row, so this needs SELECT on content_hash
      // in addition to UPDATE.
      assertThat(
              st.executeUpdate(
                  "UPDATE options_chat_message SET content = 'after edit', edited = TRUE, "
                      + "content_hash = 'hash-2' WHERE message_id = "
                      + msg
                      + " AND content_hash <> 'hash-2'"))
          .isEqualTo(1);
    }
  }

  @Test
  void writerCanRunThePaginatedRead() throws SQLException {
    try (Connection w = asRole("dashboard_writer", WRITER_PW);
        var st = w.createStatement()) {
      insertMessage(st, MSG + 10, "a");
      insertMessage(st, MSG + 11, "b");

      try (var rs =
          st.executeQuery(
              "SELECT message_id, author_name, posted_at, content, edited, deleted_at "
                  + "FROM options_chat_message WHERE channel_id = "
                  + CHANNEL
                  + " AND message_id < "
                  + (MSG + 11)
                  + " ORDER BY message_id DESC LIMIT 50")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getLong(1)).isEqualTo(MSG + 10);
      }
    }
  }

  @Test
  void writerIsDeniedDelete_soRetentionMustShipItsOwnMigration() throws SQLException {
    try (Connection w = asRole("dashboard_writer", WRITER_PW);
        var st = w.createStatement()) {
      assertDenied(() -> st.executeUpdate("DELETE FROM options_chat_message"));
      assertDenied(() -> st.executeUpdate("DELETE FROM options_chat_attachment"));
      assertDenied(() -> st.executeUpdate("DELETE FROM options_chat_embed"));
    }
  }

  @Test
  void writerIsDeniedUpdateOnEmbeds_whichAreReplacedNeverMutated() throws SQLException {
    try (Connection w = asRole("dashboard_writer", WRITER_PW);
        var st = w.createStatement()) {
      assertDenied(() -> st.executeUpdate("UPDATE options_chat_embed SET title = 'x'"));
    }
  }

  @Test
  void readonlyIsDeniedEverything_soANextJsCompromiseCannotReachChatContent() throws SQLException {
    // dashboard_readonly is the browser-facing Next.js pool. /options-chat is served through the
    // BFF, so V9 grants that role nothing at all — asserted here so a later "convenience" grant
    // cannot slip in unnoticed.
    try (Connection r = asRole("dashboard_readonly", READONLY_PW);
        var st = r.createStatement()) {
      assertDenied(() -> st.executeQuery("SELECT * FROM options_chat_message"));
      assertDenied(() -> st.executeQuery("SELECT * FROM options_chat_attachment"));
      assertDenied(() -> st.executeQuery("SELECT * FROM options_chat_embed"));
    }
  }

  private static int insertMessage(java.sql.Statement st, long messageId, String content)
      throws SQLException {
    return st.executeUpdate(
        "INSERT INTO options_chat_message (message_id, channel_id, author_name, posted_at, "
            + "content, content_hash) VALUES ("
            + messageId
            + ", "
            + CHANNEL
            + ", 'TradingTheTrend', now(), '"
            + content
            + "', 'hash-1') ON CONFLICT (message_id) DO NOTHING");
  }

  private static boolean indexExists(Connection c, String indexName) throws SQLException {
    try (var ps = c.prepareStatement("SELECT 1 FROM pg_indexes WHERE indexname = ?")) {
      ps.setString(1, indexName);
      try (var rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  private static void assertDenied(ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOf(SQLException.class)
        .satisfies(
            e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
  }
}
