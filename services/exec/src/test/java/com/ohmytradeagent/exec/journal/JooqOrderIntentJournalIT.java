package com.ohmytradeagent.exec.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.table;

import com.ohmytradeagent.contract.OrderIntent;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import org.flywaydb.core.Flyway;
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

/** Gated on {@code RUN_DB_ITS=true} — see Phase 2a's ContractActivitiesImplIT for context. */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class JooqOrderIntentJournalIT {

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  private static java.sql.Connection conn;
  private static DSLContext dsl;
  private JooqOrderIntentJournal journal;

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

  @BeforeEach
  void truncate() {
    dsl.deleteFrom(table("order_intent_journal")).execute();
    journal = new JooqOrderIntentJournal(dsl);
  }

  @Test
  void upsertIntent_firstCall_insertsRowReturnsTrue() {
    boolean inserted = journal.upsertIntent(intent("intent-A"));

    assertThat(inserted).isTrue();
    JournaledOrder row = journal.findByIntentKey("intent-A").orElseThrow();
    assertThat(row.state()).isEqualTo(OrderState.RECORDED);
    assertThat(row.brokerOrderId()).isNull();
  }

  @Test
  void upsertIntent_secondCallSameKey_returnsFalseAndDoesNotDuplicate() {
    journal.upsertIntent(intent("intent-A"));

    boolean inserted = journal.upsertIntent(intent("intent-A"));

    assertThat(inserted).isFalse();
    Long rows =
        dsl.select(org.jooq.impl.DSL.count())
            .from(table("order_intent_journal"))
            .fetchOneInto(Long.class);
    assertThat(rows).isEqualTo(1L);
  }

  @Test
  void markSubmittedIfRecorded_onRecordedRow_updatesStateReturnsTrue() {
    journal.upsertIntent(intent("intent-A"));

    boolean updated = journal.markSubmittedIfRecorded("intent-A", "stub-intent-A");

    assertThat(updated).isTrue();
    JournaledOrder row = journal.findByIntentKey("intent-A").orElseThrow();
    assertThat(row.state()).isEqualTo(OrderState.SUBMITTED);
    assertThat(row.brokerOrderId()).isEqualTo("stub-intent-A");
    assertThat(row.submittedAt()).isNotNull();
  }

  @Test
  void markSubmittedIfRecorded_twice_secondReturnsFalse() {
    journal.upsertIntent(intent("intent-A"));
    journal.markSubmittedIfRecorded("intent-A", "stub-intent-A");

    boolean second = journal.markSubmittedIfRecorded("intent-A", "stub-different");

    assertThat(second).isFalse();
    assertThat(journal.findByIntentKey("intent-A").orElseThrow().brokerOrderId())
        .isEqualTo("stub-intent-A");
  }

  @Test
  void markCancelled_flipsState() {
    journal.upsertIntent(intent("intent-A"));
    journal.markSubmittedIfRecorded("intent-A", "stub-intent-A");

    journal.markCancelled("intent-A");

    assertThat(journal.findByIntentKey("intent-A").orElseThrow().state())
        .isEqualTo(OrderState.CANCELLED);
  }

  @Test
  void markCancelFailed_leavesStateUnchangedSetsLastError() {
    journal.upsertIntent(intent("intent-A"));
    journal.markSubmittedIfRecorded("intent-A", "stub-intent-A");

    journal.markCancelFailed("intent-A", "order already filled");

    JournaledOrder row = journal.findByIntentKey("intent-A").orElseThrow();
    assertThat(row.state()).isEqualTo(OrderState.SUBMITTED);
    assertThat(row.lastError()).isEqualTo("order already filled");
  }

  @Test
  void markFilled_flipsState() {
    // Issue #165: markFilled transitions SUBMITTED → FILLED and records the
    // broker-confirmed fill detail discovered during a cancel-on-filled race.
    journal.upsertIntent(intent("intent-A"));
    journal.markSubmittedIfRecorded("intent-A", "stub-intent-A");
    // Simulate a prior cancel attempt having recorded a transient last_error —
    // markFilled must clear it once the broker confirms the fill.
    journal.markCancelFailed("intent-A", "transient broker hiccup");
    OffsetDateTime filledAt = OffsetDateTime.parse("2026-05-19T17:08:11Z");

    boolean updated = journal.markFilled("intent-A", 5L, new BigDecimal("0.84"), filledAt);

    assertThat(updated).isTrue();
    JournaledOrder row = journal.findByIntentKey("intent-A").orElseThrow();
    assertThat(row.state()).isEqualTo(OrderState.FILLED);
    assertThat(row.filledQty()).isEqualTo(5L);
    assertThat(row.avgFillPrice()).isEqualByComparingTo(new BigDecimal("0.84"));
    assertThat(row.filledAt()).isEqualTo(filledAt);
    assertThat(row.lastError()).isNull();
  }

  @Test
  void markFilled_onTerminalState_noOp() {
    // Issue #165: a repeat call on a row already in a terminal state must be a no-op
    // (returns false) — the journal already reflects the final outcome.
    journal.upsertIntent(intent("intent-A"));
    journal.markSubmittedIfRecorded("intent-A", "stub-intent-A");
    journal.markCancelled("intent-A");
    JournaledOrder before = journal.findByIntentKey("intent-A").orElseThrow();

    boolean updated =
        journal.markFilled(
            "intent-A", 5L, new BigDecimal("0.84"), OffsetDateTime.parse("2026-05-19T17:08:11Z"));

    assertThat(updated).isFalse();
    JournaledOrder after = journal.findByIntentKey("intent-A").orElseThrow();
    assertThat(after.state()).isEqualTo(OrderState.CANCELLED);
    assertThat(after.filledQty()).isNull();
    assertThat(after.avgFillPrice()).isNull();
    assertThat(after.filledAt()).isNull();
    assertThat(after.version()).isEqualTo(before.version());
  }

  @Test
  void findByIntentKey_missing_returnsEmpty() {
    assertThat(journal.findByIntentKey("nope")).isEmpty();
  }

  @Test
  void findLatestFilledByOcc_returnsMostRecentFilled() {
    // Issue #165 Phase 3: recon walks broker-held positions and looks up the most recent FILLED
    // journal row per OCC to rebuild the expected PositionWorkflow id. The partial index from
    // V3 (tenant_id, strategy_id, option_symbol, filled_at DESC) WHERE state='FILLED' makes this
    // a constant-time scan.
    String occ = "SPY   260519C00737000";
    journal.upsertIntent(intentWithOcc("intent-old", "sig-old", occ));
    journal.markSubmittedIfRecorded("intent-old", "stub-old");
    journal.markFilled(
        "intent-old", 3L, new BigDecimal("0.50"), OffsetDateTime.parse("2026-05-19T15:00:00Z"));

    journal.upsertIntent(intentWithOcc("intent-new", "sig-new", occ));
    journal.markSubmittedIfRecorded("intent-new", "stub-new");
    journal.markFilled(
        "intent-new", 5L, new BigDecimal("0.84"), OffsetDateTime.parse("2026-05-19T17:08:11Z"));

    JournaledOrder latest = journal.findLatestFilledByOcc("dev", "copytrade-v1", occ).orElseThrow();
    assertThat(latest.intentKey()).isEqualTo("intent-new");
    assertThat(latest.signalId()).isEqualTo("sig-new");
    assertThat(latest.filledQty()).isEqualTo(5L);
  }

  @Test
  void findLatestFilledByOcc_noFilledForOcc_returnsEmpty() {
    // OCC has no FILLED row → recon emits a PositionOrphan with journal_status="missing".
    journal.upsertIntent(intentWithOcc("intent-other", "sig-other", "NVDA  260516C00140000"));

    assertThat(journal.findLatestFilledByOcc("dev", "copytrade-v1", "SPY   260519C00737000"))
        .isEmpty();
  }

  @Test
  void findLatestFilledByOcc_filtersTenantAndStrategy() {
    // The partial index leaf order requires (tenant_id, strategy_id, option_symbol) — a FILLED
    // entry under a different tenant must not leak across.
    String occ = "SPY   260519C00737000";
    OrderIntent foreign = intentWithOcc("intent-foreign", "sig-foreign", occ);
    foreign.setTenantId("other-tenant");
    journal.upsertIntent(foreign);
    journal.markSubmittedIfRecorded("intent-foreign", "stub-foreign");
    journal.markFilled(
        "intent-foreign", 9L, new BigDecimal("1.20"), OffsetDateTime.parse("2026-05-19T17:00:00Z"));

    assertThat(journal.findLatestFilledByOcc("dev", "copytrade-v1", occ)).isEmpty();
    assertThat(journal.findLatestFilledByOcc("other-tenant", "copytrade-v1", occ)).isPresent();
  }

  @Test
  void findByBrokerOrderId_returnsRowAfterSubmit() {
    journal.upsertIntent(intent("intent-A"));
    journal.markSubmittedIfRecorded("intent-A", "brk-A");

    JournaledOrder row = journal.findByBrokerOrderId("brk-A").orElseThrow();
    assertThat(row.intentKey()).isEqualTo("intent-A");
    assertThat(row.brokerOrderId()).isEqualTo("brk-A");
  }

  @Test
  void findByBrokerOrderId_returnsEmpty_whenAbsent() {
    assertThat(journal.findByBrokerOrderId("never-existed")).isEmpty();
  }

  @Test
  void findSubmittedOlderThan_returnsOnlyOldSubmittedRows() {
    OffsetDateTime now = OffsetDateTime.parse("2026-05-23T20:00:00Z");
    OffsetDateTime old = now.minusMinutes(5);
    OffsetDateTime recent = now.minusSeconds(5);

    OrderIntent oldIntent = intent("old-intent");
    oldIntent.setRecordedAt(old);
    journal.upsertIntent(oldIntent);
    journal.markSubmittedIfRecorded("old-intent", "brk-old");
    dsl.update(table("order_intent_journal"))
        .set(org.jooq.impl.DSL.field("submitted_at"), old)
        .where(org.jooq.impl.DSL.field("intent_key").eq("old-intent"))
        .execute();

    OrderIntent recentIntent = intent("recent-intent");
    recentIntent.setRecordedAt(recent);
    journal.upsertIntent(recentIntent);
    journal.markSubmittedIfRecorded("recent-intent", "brk-recent");
    dsl.update(table("order_intent_journal"))
        .set(org.jooq.impl.DSL.field("submitted_at"), recent)
        .where(org.jooq.impl.DSL.field("intent_key").eq("recent-intent"))
        .execute();

    var rows = journal.findSubmittedOlderThan(now.minusMinutes(1), 10);
    assertThat(rows).extracting(JournaledOrder::intentKey).containsExactly("old-intent");
  }

  @Test
  void findSubmittedOlderThan_respectsLimit() {
    OffsetDateTime old = OffsetDateTime.parse("2026-05-23T19:00:00Z");
    for (int i = 0; i < 5; i++) {
      String key = "intent-" + i;
      journal.upsertIntent(intent(key));
      journal.markSubmittedIfRecorded(key, "brk-" + i);
      dsl.update(table("order_intent_journal"))
          .set(org.jooq.impl.DSL.field("submitted_at"), old.plusSeconds(i))
          .where(org.jooq.impl.DSL.field("intent_key").eq(key))
          .execute();
    }
    var rows = journal.findSubmittedOlderThan(OffsetDateTime.parse("2026-05-23T20:00:00Z"), 3);
    assertThat(rows).hasSize(3);
  }

  @Test
  void findSubmittedOlderThan_excludesRecorded() {
    journal.upsertIntent(intent("intent-A"));

    var rows = journal.findSubmittedOlderThan(OffsetDateTime.parse("2030-01-01T00:00:00Z"), 10);
    assertThat(rows).isEmpty();
  }

  @Test
  void findByBrokerOrderId_returnsEmpty_forRecordedRow() {
    // RECORDED rows have no broker_order_id yet; the partial index excludes them and the lookup
    // returns empty rather than matching on NULL.
    journal.upsertIntent(intent("intent-A"));

    assertThat(journal.findByBrokerOrderId("anything")).isEmpty();
  }

  private OrderIntent intent(String key) {
    OrderIntent i = new OrderIntent();
    i.setSchemaVersion(1L);
    i.setIntentKey(key);
    i.setSignalId("sig-1");
    i.setTenantId("dev");
    i.setStrategyId("copytrade-v1");
    i.setBrokerTarget(OrderIntent.BrokerTarget.PAPER);
    i.setOptionSymbol("NVDA  260516C00140000");
    i.setSide(OrderIntent.Side.BUY);
    i.setQty(1L);
    i.setLimitPrice(new BigDecimal("2.30"));
    i.setRecordedAt(OffsetDateTime.parse("2026-05-13T17:22:31Z"));
    return i;
  }

  private OrderIntent intentWithOcc(String key, String signalId, String occ) {
    OrderIntent i = intent(key);
    i.setSignalId(signalId);
    i.setOptionSymbol(occ);
    return i;
  }
}
