package com.ohmytradeagent.tdbff.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real-Postgres coverage for V13 (#783 {@code trade_context}). The structural lock in {@link
 * DashboardMigrationSqlStructureTest} asserts the SQL we WILL apply; this asserts what Postgres
 * actually permits AS the least-privilege {@code trade_context_writer} role the orchestrator's
 * recorder connects with — and it proves the two idempotency invariants the issue demands with the
 * SAME SQL shapes the recorder issues (pinned from the producing side by the orchestrator's {@code
 * TradeContextRepositorySqlTest}):
 *
 * <ul>
 *   <li><b>A poller restart must not duplicate entry rows</b> — the entry INSERT is {@code ON
 *       CONFLICT (signal_id, tenant_id) DO NOTHING}, whose arbiter probe needs the SELECT grant
 *       (42501 without it — the V9 lesson).
 *   <li><b>A poller restart must not reset MFE/MAE</b> — the per-poll ratchet is {@code
 *       GREATEST/LEAST} against the stored value, so replaying an older bid is a no-op.
 * </ul>
 *
 * Gated on {@code RUN_DB_ITS=true} like the module's other DB ITs (CI sets it; a Docker-less
 * workstation skips cleanly).
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class TradeContextMigrationIT {

  private static final String WRITER_PW = "tc-writer-test-pw";
  private static final String INSUFFICIENT_PRIVILEGE = "42501";

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/dashboard")
        .placeholders(
            Map.of(
                "dashboard_readonly_password", "readonly-test-pw",
                "dashboard_writer_password", "writer-test-pw",
                "trade_context_writer_password", WRITER_PW))
        .load()
        .migrate();
  }

  private static Connection asWriter() throws SQLException {
    return DriverManager.getConnection(postgres.getJdbcUrl(), "trade_context_writer", WRITER_PW);
  }

  /** The recorder's entry upsert shape (same as the orchestrator repository issues). */
  private static int insertEntry(Statement st, String signalId, String bid) throws SQLException {
    return st.executeUpdate(
        "INSERT INTO trade_context (signal_id, tenant_id, strategy_id, workflow_id, "
            + "contract_symbol, entry_premium, entry_qty, entry_bid, entry_quote_state) VALUES ('"
            + signalId
            + "', 'acme', 'copytrade-v1', 'wf-1', 'NVDA  270115C00140000', 2.00, 3, "
            + bid
            + ", 'ok') ON CONFLICT (signal_id, tenant_id) DO NOTHING");
  }

  /** The recorder's per-poll ratchet shape: monotonic MFE/MAE, current workflow id, open status. */
  private static int ratchet(Statement st, String signalId, String bid) throws SQLException {
    return st.executeUpdate(
        "UPDATE trade_context SET "
            + "mfe_premium = GREATEST(COALESCE(mfe_premium, "
            + bid
            + "), "
            + bid
            + "), mae_premium = LEAST(COALESCE(mae_premium, "
            + bid
            + "), "
            + bid
            + "), workflow_id = 'wf-1', status = 'open', updated_at = now() "
            + "WHERE signal_id = '"
            + signalId
            + "' AND tenant_id = 'acme'");
  }

  @Test
  void entryInsertIsIdempotent_aRestartNeverDuplicatesTheRow() throws SQLException {
    try (Connection w = asWriter();
        var st = w.createStatement()) {
      assertThat(insertEntry(st, "sig-idem", "2.10")).isEqualTo(1);
      // The restart path: a fresh process observes the same open position again. Without SELECT on
      // the arbiter index this raises 42501; with it, a silent no-op that keeps the FIRST snapshot.
      assertThat(insertEntry(st, "sig-idem", "9.99")).as("replay is a silent no-op").isEqualTo(0);

      try (var rs =
          st.executeQuery(
              "SELECT count(*), min(entry_bid) FROM trade_context WHERE signal_id = 'sig-idem'")) {
        rs.next();
        assertThat(rs.getInt(1)).isEqualTo(1);
        assertThat(rs.getBigDecimal(2))
            .as("original entry snapshot kept")
            .isEqualByComparingTo("2.10");
      }
    }
  }

  @Test
  void mfeMaeRatchetIsMonotonic_replayingAnOlderBidNeverResetsIt() throws SQLException {
    try (Connection w = asWriter();
        var st = w.createStatement()) {
      insertEntry(st, "sig-ratchet", "2.00");
      ratchet(st, "sig-ratchet", "2.00");
      ratchet(st, "sig-ratchet", "3.00"); // new high
      ratchet(st, "sig-ratchet", "1.50"); // new low
      ratchet(st, "sig-ratchet", "2.50"); // interior: must move NEITHER bound

      try (var rs =
          st.executeQuery(
              "SELECT mfe_premium, mae_premium FROM trade_context "
                  + "WHERE signal_id = 'sig-ratchet'")) {
        rs.next();
        assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("3.00");
        assertThat(rs.getBigDecimal(2)).isEqualByComparingTo("1.50");
      }
    }
  }

  @Test
  void writerCanCloseARow_butNeverDelete() throws SQLException {
    try (Connection w = asWriter();
        var st = w.createStatement()) {
      insertEntry(st, "sig-close", "2.00");
      assertThat(
              st.executeUpdate(
                  "UPDATE trade_context SET status = 'closed', closed_at = now(), "
                      + "exit_bid = 0.90, hold_minutes = 42, updated_at = now() "
                      + "WHERE signal_id = 'sig-close' AND tenant_id = 'acme' "
                      + "AND status = 'open'"))
          .isEqualTo(1);

      // Retention is indefinite by design; the recorder role must be unable to destroy the corpus.
      assertThatThrownBy(() -> st.executeUpdate("DELETE FROM trade_context"))
          .isInstanceOf(SQLException.class)
          .extracting(e -> ((SQLException) e).getSQLState())
          .isEqualTo(INSUFFICIENT_PRIVILEGE);
    }
  }
}
