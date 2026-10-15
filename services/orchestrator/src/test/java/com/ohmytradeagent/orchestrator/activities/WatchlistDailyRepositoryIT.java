package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.orchestrator.activities.WatchlistDailyRepository.Idea;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
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
 * {@link WatchlistDailyRepository} against the migrated schema. The unit test mocks the DSL, which
 * is how a 51-char production message id met a VARCHAR(32) column only in prod (2026-10-15).
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class WatchlistDailyRepositoryIT {

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  // The id shape the sidecar actually sends: chat-messages-<channel>-<message>.
  private static final String REAL_MSG_ID = "chat-messages-769797819770732554-1560281136490610750";

  private static Connection conn;
  private static DSLContext dsl;

  @BeforeAll
  static void initDb() throws Exception {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
    conn =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    dsl = DSL.using(conn, SQLDialect.POSTGRES);
  }

  @AfterAll
  static void closeDb() throws Exception {
    if (conn != null) conn.close();
  }

  @Test
  void record_storesARealDiscordMessageId_andIsWriteOncePerDay() {
    WatchlistDailyRepository repo = new WatchlistDailyRepository(dsl, new ObjectMapper());
    LocalDate day = LocalDate.of(2026, 10, 15);

    repo.record(
        day,
        REAL_MSG_ID,
        "TradingTheTrend",
        List.of(new Idea("SPY", "C", "772", "ABOVE", new BigDecimal("771.00"))));
    repo.record(day, "chat-messages-1-2", "someone-else", List.of());

    List<Record> rows = dsl.fetch("SELECT source_message_id, author FROM watchlist_daily");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("source_message_id", String.class)).isEqualTo(REAL_MSG_ID);
    assertThat(rows.get(0).get("author", String.class)).isEqualTo("TradingTheTrend");
  }
}
