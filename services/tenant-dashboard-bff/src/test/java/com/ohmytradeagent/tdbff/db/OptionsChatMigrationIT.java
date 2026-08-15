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

  /** Holds the planner-shaping bulk rows, off in a channel nothing else reads. */
  private static final long BULK_CHANNEL = 1L;

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
      assertThat(indexExists(c, "options_chat_message_posted_at_idx"))
          .as("the named index backing the retention sweep")
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

      // Scoped to THIS test's snowflake: the container is static and sibling tests insert their own
      // rows, so an unscoped count(*) would pass only while this method happened to run first.
      try (var rs =
          st.executeQuery("SELECT count(*) FROM options_chat_message WHERE message_id = " + MSG)) {
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
    }
  }

  @Test
  void writerCanBackfillChildrenOntoAnAlreadyStoredMessage() throws SQLException {
    // Discord resolves link previews and image accessories seconds AFTER the message element
    // appears, so a message caught on first render is stored bare. The ingest backfills children
    // onto an existing parent whose child set is empty; this proves V9's grants allow that shape
    // (count(*) then INSERT) with no UPDATE and no new privilege.
    long msg = MSG + 3;
    try (Connection w = asRole("dashboard_writer", WRITER_PW);
        var st = w.createStatement()) {
      insertMessage(st, msg, "chart incoming");
      // Replay finds the parent present...
      assertThat(insertMessage(st, msg, "chart incoming")).isEqualTo(0);

      try (var rs =
          st.executeQuery(
              "SELECT count(*) FROM options_chat_attachment WHERE message_id = " + msg)) {
        rs.next();
        assertThat(rs.getInt(1)).as("no children yet").isZero();
      }
      assertThat(
              st.executeUpdate(
                  "INSERT INTO options_chat_attachment (message_id, ordinal, kind, source_url) "
                      + "VALUES ("
                      + msg
                      + ", 0, 'image', 'https://cdn.discordapp.com/late.png')"))
          .isEqualTo(1);

      try (var rs =
          st.executeQuery(
              "SELECT count(*) FROM options_chat_attachment WHERE message_id = " + msg)) {
        rs.next();
        assertThat(rs.getInt(1)).isEqualTo(1);
      }
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
  void retentionCanDeleteAMessageAndItsChildrenCascade_withoutAnyChildDeleteGrant()
      throws SQLException {
    // V12 grants DELETE on the PARENT ONLY, on the claim that PostgreSQL runs a foreign key's
    // ON DELETE CASCADE through the constraint's own internal triggers rather than as the invoking
    // role. If that were wrong, retention would 42501 in production every night at 03:30 and the
    // store would grow forever while looking configured. Proven here rather than assumed.
    long msg = MSG + 20;
    try (Connection w = asRole("dashboard_writer", WRITER_PW);
        var st = w.createStatement()) {
      insertMessage(st, msg, "expires");
      st.executeUpdate(
          "INSERT INTO options_chat_attachment (message_id, ordinal, kind, source_url) VALUES ("
              + msg
              + ", 0, 'image', 'https://cdn.discordapp.com/old.png')");
      st.executeUpdate(
          "INSERT INTO options_chat_embed (message_id, ordinal, title) VALUES ("
              + msg
              + ", 0, 'old')");

      assertThat(st.executeUpdate("DELETE FROM options_chat_message WHERE message_id = " + msg))
          .isEqualTo(1);

      try (var rs =
          st.executeQuery(
              "SELECT (SELECT count(*) FROM options_chat_attachment WHERE message_id = "
                  + msg
                  + ") + (SELECT count(*) FROM options_chat_embed WHERE message_id = "
                  + msg
                  + ")")) {
        rs.next();
        assertThat(rs.getInt(1)).as("children cascaded with the parent").isZero();
      }
    }
  }

  @Test
  void theRetentionSweepsExactQueryCanUseThePostedAtIndex() throws SQLException {
    // An index EXISTING does not mean the sweep BENEFITS from it: the point of ordering by
    // posted_at is that the index already holds that order, so the delete batch streams off the
    // front and stops at 500. If the plan sorts first, the sweep touches every expired row on the
    // way to picking 500 — precisely the behaviour the index was added to avoid, and it degrades
    // exactly when the table is big, which is when it matters.
    //
    // THE ROWS ARE THE TEST. Loaded to production shape first, deliberately: on the dozen rows the
    // sibling tests leave behind, the whole table is under the 500 limit, so "sort everything" is
    // genuinely cheaper and Postgres is right to choose it. Asserting against that tiny table
    // measured nothing and failed — the honest question is which plan wins once the table is large
    // enough for the choice to have a cost, so the table is made large enough to ask it.
    //
    // Chronological insert order matches how a chat mirror actually fills (append-only, in time
    // order), which keeps posted_at correlated with physical order the way production's is.
    try (Connection c = asSuperuser();
        var st = c.createStatement()) {
      // A channel of its own, so these rows cannot perturb any channel-scoped sibling assertion.
      st.executeUpdate(
          "INSERT INTO options_chat_message (message_id, channel_id, author_name, posted_at,"
              + " content, content_hash) SELECT g, "
              + BULK_CHANNEL
              + ", 'bulk', now() - ((5000 - g) || ' minutes')::interval, 'c', 'h'"
              + " FROM generate_series(1, 5000) g");
      // Without stats the planner works off a default estimate and the choice is not a real one.
      st.execute("ANALYZE options_chat_message");

      StringBuilder plan = new StringBuilder();
      try (var rs =
          st.executeQuery(
              "EXPLAIN SELECT message_id FROM options_chat_message"
                  + " WHERE posted_at < now() ORDER BY posted_at ASC LIMIT 500")) {
        while (rs.next()) {
          plan.append(rs.getString(1)).append('\n');
        }
      }

      // No enable_seqscan coercion: at this size the planner reaches for the index unprompted, so
      // this asserts the decision production will actually get rather than one forced in a test.
      assertThat(plan.toString())
          .as("the sweep's filter+order must be servable by the posted_at index")
          .contains("options_chat_message_posted_at_idx");
      assertThat(plan.toString())
          .as("and with no sort step, because the index is already in posted_at order")
          .doesNotContain("Sort");
    }
  }

  @Test
  void writerStillCannotDeleteChildrenDirectly_onlyViaTheCascade() throws SQLException {
    // The grant's blast radius: removing a message takes its own media with it, but the writer can
    // never empty the attachment table outright.
    try (Connection w = asRole("dashboard_writer", WRITER_PW);
        var st = w.createStatement()) {
      assertDenied(() -> st.executeUpdate("DELETE FROM options_chat_attachment"));
      assertDenied(() -> st.executeUpdate("DELETE FROM options_chat_embed"));
    }
  }

  @Test
  void writerIsStillDeniedUpdateOnMessageAndEmbed_afterV10WidenedAttachmentsOnly()
      throws SQLException {
    // Phase 1 issues no UPDATE, so V9 grants none. Phase 4 (media fill: SET bytes, fetch_state) and
    // Phase 6 (edit reconcile: SET content WHERE content_hash <> ?) each add their own grant with
    // the code that needs it. These assertions are what force that to be a conscious act rather
    // than something already lying around — flipping one of them is the signal to review the
    // widening.
    try (Connection w = asRole("dashboard_writer", WRITER_PW);
        var st = w.createStatement()) {
      assertDenied(() -> st.executeUpdate("UPDATE options_chat_message SET edited = TRUE"));
      assertDenied(() -> st.executeUpdate("UPDATE options_chat_embed SET title = 'x'"));
      // options_chat_attachment IS now updatable — V10 widened it for the Phase 4 media fill, which
      // is the deliberate-widening flow this assertion set exists to force.
      st.executeUpdate("UPDATE options_chat_attachment SET fetch_state = 'ok' WHERE id = -1");
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
