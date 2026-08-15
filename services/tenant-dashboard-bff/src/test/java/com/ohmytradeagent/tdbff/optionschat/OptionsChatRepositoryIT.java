package com.ohmytradeagent.tdbff.optionschat;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository.IngestAttachment;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository.IngestEmbed;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository.IngestMessage;
import com.ohmytradeagent.tdbff.optionschat.OptionsChatRepository.StoredMessage;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs {@link OptionsChatRepository}'s ACTUAL SQL against a real Postgres, as {@code
 * dashboard_writer}.
 *
 * <p>THIS TEST EXISTS BECAUSE ITS ABSENCE COST A PRODUCTION OUTAGE. {@code OptionsChatMigrationIT}
 * proved the V9 grants, but with hand-written SQL using {@code now()} — it never executed the
 * repository's own statements. The WebMvc tests mock the repository. So the one thing nothing
 * covered was the repository's SQL and its parameter binding, and that is exactly what broke: jOOQ
 * renders an {@code OffsetDateTime} bind as a STRING for PostgreSQL, so the uncast {@code
 * posted_at} parameter failed with 42804 and every ingest 500'd.
 *
 * <p>The lesson generalises past the one cast: a migration test and a mocked web test can both be
 * green while the layer between them has never run at all.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class OptionsChatRepositoryIT {

  private static final String READONLY_PW = "readonly-test-pw";
  private static final String WRITER_PW = "writer-test-pw";
  private static final long CHANNEL = 786109983065505792L;

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  private static OptionsChatRepository repo;

  @BeforeAll
  static void migrateAndWire() {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/dashboard")
        .placeholders(
            Map.of(
                "dashboard_readonly_password", READONLY_PW,
                "dashboard_writer_password", WRITER_PW))
        .load()
        .migrate();
    // As the least-privilege role the BFF actually connects as — so a missing grant fails here too.
    org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
    ds.setUrl(postgres.getJdbcUrl());
    ds.setUser("dashboard_writer");
    ds.setPassword(WRITER_PW);
    repo = new OptionsChatRepository(DSL.using(ds, SQLDialect.POSTGRES));
  }

  private static IngestMessage message(long id, String content) {
    return message(
        id,
        content,
        OffsetDateTime.of(2026, 8, 13, 14, 3, 11, 0, ZoneOffset.UTC),
        List.of(),
        List.of());
  }

  /**
   * The same author fixture posted at a chosen time, carrying one attachment and one embed — so a
   * row built this way exercises the child-cascade as well as the parent.
   */
  private static IngestMessage messageAt(long id, OffsetDateTime postedAt) {
    return message(
        id,
        "old news",
        postedAt,
        List.of(
            new IngestAttachment("image", "https://cdn.discordapp.com/o.png", "o.png", 1, 1, 1)),
        List.of(new IngestEmbed("t", "d", "https://example.com", "au", "f", null)));
  }

  private static IngestMessage message(
      long id,
      String content,
      OffsetDateTime postedAt,
      List<IngestAttachment> attachments,
      List<IngestEmbed> embeds) {
    return new IngestMessage(
        id,
        "TradingTheTrend",
        "#ff0004",
        // DISTINCT, non-null, and asserted on the way back out. author_name, author_color and
        // author_avatar_url are three adjacent String components of a POSITIONAL record: a
        // transposition compiles, passes every mocked web test, and silently stores the colour in
        // the avatar column — the page then renders <img src="#ff0004"> and every name loses its
        // colour. Leaving avatar null here made that swap undetectable.
        "https://cdn.discordapp.com/avatars/1/av.png",
        postedAt,
        content,
        null,
        false,
        attachments,
        embeds);
  }

  @Test
  void storesAMessageWithAnOffsetDateTime_theBindThatBrokeProduction() {
    long id = 9_000_000_000_000_000_001L;

    assertThat(repo.ingest(CHANNEL, List.of(message(id, "NVDA looking strong")))).isEqualTo(1);

    List<StoredMessage> page = repo.recent(CHANNEL, null, 50);
    StoredMessage stored = page.stream().filter(m -> m.messageId() == id).findFirst().orElseThrow();
    assertThat(stored.content()).isEqualTo("NVDA looking strong");
    // The three adjacent Strings, each pinned to its own column.
    assertThat(stored.authorName()).isEqualTo("TradingTheTrend");
    assertThat(stored.authorColor()).isEqualTo("#ff0004");
    assertThat(stored.authorAvatarUrl()).isEqualTo("https://cdn.discordapp.com/avatars/1/av.png");
    // Round-trips as an instant, not a string.
    assertThat(stored.postedAt()).isNotNull();
    assertThat(stored.postedAt().toInstant())
        .isEqualTo(OffsetDateTime.of(2026, 8, 13, 14, 3, 11, 0, ZoneOffset.UTC).toInstant());
  }

  @Test
  void aReplayIsANoOpAndDoesNotDuplicate() {
    long id = 9_000_000_000_000_000_002L;
    assertThat(repo.ingest(CHANNEL, List.of(message(id, "first")))).isEqualTo(1);
    assertThat(repo.ingest(CHANNEL, List.of(message(id, "first")))).isZero();

    assertThat(repo.recent(CHANNEL, null, 200).stream().filter(m -> m.messageId() == id).count())
        .isEqualTo(1);
  }

  @Test
  void storesChildrenAndServesThemWithoutEverProjectingBytes() {
    long id = 9_000_000_000_000_000_003L;
    IngestMessage m =
        new IngestMessage(
            id,
            "TradingTheTrend",
            "#ff0004",
            null,
            OffsetDateTime.now(ZoneOffset.UTC),
            "chart",
            null,
            false,
            List.of(
                new IngestAttachment(
                    "image", "https://cdn.discordapp.com/a.png", "a.png", 800, 600, 1234)),
            List.of(new IngestEmbed("t", "d", "https://example.com", "au", "f", null)));

    assertThat(repo.ingest(CHANNEL, List.of(m))).isEqualTo(1);

    StoredMessage stored =
        repo.recent(CHANNEL, null, 200).stream()
            .filter(x -> x.messageId() == id)
            .findFirst()
            .orElseThrow();
    assertThat(stored.attachments()).hasSize(1);
    assertThat(stored.attachments().get(0).kind()).isEqualTo("image");
    // content_type stays null until Phase 4's transcode sets it — never the caller's claim.
    assertThat(stored.attachments().get(0).contentType()).isNull();
    assertThat(stored.embeds()).hasSize(1);
    assertThat(stored.embeds().get(0).url()).isEqualTo("https://example.com");
  }

  @Test
  void backfillsChildrenOntoAMessageThatWasStoredBeforeDiscordResolvedThem() {
    long id = 9_000_000_000_000_000_004L;
    // First scrape: caught on render, accessories not resolved yet.
    assertThat(repo.ingest(CHANNEL, List.of(message(id, "chart incoming")))).isEqualTo(1);

    // Later sweep: same message, now with its attachment. The parent insert loses, so only the
    // backfill can save the chart from being lost for the row's whole retention.
    IngestMessage withChild =
        new IngestMessage(
            id,
            "TradingTheTrend",
            "#ff0004",
            null,
            OffsetDateTime.now(ZoneOffset.UTC),
            "chart incoming",
            null,
            false,
            List.of(
                new IngestAttachment(
                    "image", "https://cdn.discordapp.com/late.png", "late.png", null, null, null)),
            List.of());
    assertThat(repo.ingest(CHANNEL, List.of(withChild))).isZero();

    StoredMessage stored =
        repo.recent(CHANNEL, null, 200).stream()
            .filter(x -> x.messageId() == id)
            .findFirst()
            .orElseThrow();
    assertThat(stored.attachments()).hasSize(1);
    assertThat(stored.attachments().get(0).filename()).isEqualTo("late.png");

    // And a THIRD sweep must not duplicate it.
    assertThat(repo.ingest(CHANNEL, List.of(withChild))).isZero();
    assertThat(
            repo.recent(CHANNEL, null, 200).stream()
                .filter(x -> x.messageId() == id)
                .findFirst()
                .orElseThrow()
                .attachments())
        .hasSize(1);
  }

  // THE RETENTION TESTS SHARE ONE TABLE AND deleteOlderThan HAS NO CHANNEL PREDICATE, so time is
  // the only axis that can separate them. Two rules keep them independent, and a third test must
  // follow both: every non-retention row in this class is dated 2026 (see message() above), and
  // each retention test owns a disjoint pre-2021 era that it drains before it returns. The test
  // asserting an exact "and nothing else" count deliberately owns the OLDEST era, so no sibling's
  // rows can ever fall below its cutoff regardless of execution order.

  @Test
  void retentionDeletesOnlyRowsPastTheCutoff_andBindsTheCutoffAsATimestamp() {
    // deleteOlderThan binds an OffsetDateTime — the SAME bind that 500'd every ingest until it was
    // cast (see this class's header). An uncast cutoff here would not fail at build time and would
    // not fail any mocked test; it would fail silently at 03:30 every night, and the only visible
    // symptom would be a store that quietly never stops growing. So the sweep's real SQL runs here.
    long old1 = 9_200_000_000_000_000_001L;
    long old2 = 9_200_000_000_000_000_002L;
    long keep = 9_200_000_000_000_000_003L;
    OffsetDateTime era = OffsetDateTime.of(2018, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    repo.ingest(
        CHANNEL,
        List.of(
            messageAt(old1, era),
            messageAt(old2, era.plusDays(1)),
            messageAt(keep, OffsetDateTime.now(ZoneOffset.UTC))));

    // Both aged rows carry an attachment and an embed, so this also runs the ON DELETE CASCADE as
    // dashboard_writer: V12 grants DELETE on the parent only, and a missing child grant would
    // surface right here as 42501 rather than as a wrong count.
    OffsetDateTime cutoff = OffsetDateTime.of(2018, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    assertThat(repo.deleteOlderThan(cutoff, 500))
        .as("the aged rows, and nothing else")
        .isEqualTo(2);

    assertThat(repo.recent(CHANNEL, null, 500))
        .extracting(StoredMessage::messageId)
        .doesNotContain(old1, old2)
        .contains(keep);
  }

  @Test
  void retentionNeverDeletesMoreThanTheBatchLimit_soOneSweepCannotStallTheDatabase() {
    // The sweep is batched precisely so a first run against a long-neglected table takes many small
    // locks instead of one enormous one. If LIMIT were dropped or misbound, this is the only place
    // that notices before production does.
    long base = 9_210_000_000_000_000_000L;
    OffsetDateTime era = OffsetDateTime.of(2019, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    repo.ingest(
        CHANNEL,
        List.of(
            messageAt(base, era),
            messageAt(base + 1, era.plusMinutes(1)),
            messageAt(base + 2, era.plusMinutes(2)),
            messageAt(base + 3, era.plusMinutes(3)),
            messageAt(base + 4, era.plusMinutes(4))));

    OffsetDateTime cutoff = OffsetDateTime.of(2019, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    assertThat(repo.deleteOlderThan(cutoff, 2)).isEqualTo(2);
    assertThat(repo.deleteOlderThan(cutoff, 2)).isEqualTo(2);
    assertThat(repo.deleteOlderThan(cutoff, 2)).as("the tail").isEqualTo(1);
    assertThat(repo.deleteOlderThan(cutoff, 2)).as("and then it is drained").isZero();
  }

  @Test
  void theCursorPagesBackwardsWithoutRepeatingARow() {
    long base = 9_100_000_000_000_000_000L;
    for (int i = 0; i < 5; i++) {
      repo.ingest(CHANNEL, List.of(message(base + i, "m" + i)));
    }
    List<StoredMessage> newest = repo.recent(CHANNEL, base + 4, 2);
    assertThat(newest.stream().map(StoredMessage::messageId)).containsExactly(base + 3, base + 2);
  }
}
