package com.ohmytradeagent.exec.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.table;

import com.ohmytradeagent.contract.OrderIntent;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDate;
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
        .locations("classpath:db/exec")
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
  void markClosedAlreadyFlat_onRecordedRow_flipsToCancelledSetsLastErrorReturnsTrue() {
    // PLAN-over-exit-422: a broker-confirmed over-exit terminalizes RECORDED -> CANCELLED with the
    // benign reason in last_error.
    journal.upsertIntent(intent("intent-A"));

    boolean updated = journal.markClosedAlreadyFlat("intent-A", "benign over-exit: flat");

    assertThat(updated).isTrue();
    JournaledOrder row = journal.findByIntentKey("intent-A").orElseThrow();
    assertThat(row.state()).isEqualTo(OrderState.CANCELLED);
    assertThat(row.lastError()).isEqualTo("benign over-exit: flat");
  }

  @Test
  void markClosedAlreadyFlat_twice_secondReturnsFalse_idempotent() {
    journal.upsertIntent(intent("intent-A"));
    journal.markClosedAlreadyFlat("intent-A", "benign over-exit: flat");

    boolean second = journal.markClosedAlreadyFlat("intent-A", "benign over-exit: flat again");

    assertThat(second).isFalse();
    // The first reason stands; the no-op retry did not overwrite it.
    assertThat(journal.findByIntentKey("intent-A").orElseThrow().lastError())
        .isEqualTo("benign over-exit: flat");
  }

  @Test
  void markClosedAlreadyFlat_onSubmittedRow_noOp() {
    // Guard is state='RECORDED' only — a row that already reached SUBMITTED is not a benign
    // over-exit and must be left untouched.
    journal.upsertIntent(intent("intent-A"));
    journal.markSubmittedIfRecorded("intent-A", "stub-intent-A");

    boolean updated = journal.markClosedAlreadyFlat("intent-A", "benign over-exit: flat");

    assertThat(updated).isFalse();
    assertThat(journal.findByIntentKey("intent-A").orElseThrow().state())
        .isEqualTo(OrderState.SUBMITTED);
  }

  @Test
  void markErrored_onRecordedRow_flipsToErroredSetsLastErrorReturnsTrue() {
    // Phase 2: a terminal, non-retryable account-orders-blocked rejection terminalizes the place
    // path RECORDED -> ERRORED with the broker reason in last_error (distinct from markPlaceFailed,
    // which keeps RECORDED for retry).
    journal.upsertIntent(intent("intent-A"));

    boolean updated = journal.markErrored("intent-A", "account orders blocked: 40310000");

    assertThat(updated).isTrue();
    JournaledOrder row = journal.findByIntentKey("intent-A").orElseThrow();
    assertThat(row.state()).isEqualTo(OrderState.ERRORED);
    assertThat(row.lastError()).isEqualTo("account orders blocked: 40310000");
  }

  @Test
  void markErrored_twice_secondReturnsFalse_idempotent() {
    journal.upsertIntent(intent("intent-A"));
    journal.markErrored("intent-A", "account orders blocked: 40310000");

    boolean second = journal.markErrored("intent-A", "account orders blocked again");

    assertThat(second).isFalse();
    assertThat(journal.findByIntentKey("intent-A").orElseThrow().lastError())
        .isEqualTo("account orders blocked: 40310000");
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
  void findLatestFilledByOcc_paddedJournalRow_matchesCompactBrokerOcc() {
    // Issue #243: the journal persists the *padded* 21-char OCC (OccSymbol.of pads the root to 6
    // chars with %-6s) while the broker reports the *compact* form (Alpaca strips the spaces on
    // order placement and returns the compact symbol). Recon passes the broker's compact OCC to
    // findLatestFilledByOcc, so the lookup must be padding-agnostic or recon falsely reports the
    // owned position as a "missing"-journal PositionOrphan. The query strips space-padding on both
    // sides so the padded stored row resolves under the compact broker OCC.
    String paddedOcc = "SPY   260519C00737000";
    String compactOcc = "SPY260519C00737000";
    journal.upsertIntent(intentWithOcc("intent-padded", "sig-padded", paddedOcc));
    journal.markSubmittedIfRecorded("intent-padded", "stub-padded");
    journal.markFilled(
        "intent-padded", 5L, new BigDecimal("0.84"), OffsetDateTime.parse("2026-05-19T17:08:11Z"));

    JournaledOrder latest =
        journal.findLatestFilledByOcc("dev", "copytrade-v1", compactOcc).orElseThrow();
    assertThat(latest.intentKey()).isEqualTo("intent-padded");
    assertThat(latest.signalId()).isEqualTo("sig-padded");
    assertThat(latest.optionSymbol()).isEqualTo(paddedOcc);
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
  void findSubmittedOlderThan_excludesNonSubmittedStates() {
    // RECORDED — never reached SUBMITTED yet.
    journal.upsertIntent(intent("intent-recorded"));

    // FILLED — the only "exit" state that records fill detail; ensure it's excluded.
    journal.upsertIntent(intent("intent-filled"));
    journal.markSubmittedIfRecorded("intent-filled", "brk-filled");
    journal.markFilled(
        "intent-filled", 1L, new BigDecimal("1.00"), OffsetDateTime.parse("2026-05-24T00:00:00Z"));

    // CANCELLED — terminal cancel from the broker.
    journal.upsertIntent(intent("intent-cancelled"));
    journal.markSubmittedIfRecorded("intent-cancelled", "brk-cancelled");
    journal.markCancelled("intent-cancelled");

    var rows = journal.findSubmittedOlderThan(OffsetDateTime.parse("2030-01-01T00:00:00Z"), 10);
    assertThat(rows).isEmpty();
  }

  @Test
  void upsertIntent_storesBoundedClientOrderId_notRawIntentKey() {
    // Issue #295: a 161-char exit intent_key is stored as the long PK, but the client_order_id
    // column holds the BOUNDED (≤128) value the broker receives. findByClientOrderId resolves the
    // row by that bounded value — powering the fill-dispatcher WS-race fallback.
    String exitKey =
        "t-dev/s-copytrade-v1/pos/TSLA  260529C00435000/"
            + "chat-messages-769797179992571914-1509927843260268616:0"
            + ":exit:chat-messages-769797179992571914-1509928607168860170:0";
    journal.upsertIntent(intentWithOcc(exitKey, "sig-stc", "TSLA  260529C00435000"));

    JournaledOrder row = journal.findByIntentKey(exitKey).orElseThrow();
    assertThat(row.clientOrderId().length()).isLessThanOrEqualTo(128);
    assertThat(row.clientOrderId()).isNotEqualTo(exitKey);
    assertThat(row.clientOrderId())
        .isEqualTo(com.ohmytradeagent.exec.broker.ClientOrderId.forIntent(exitKey));

    JournaledOrder byCid = journal.findByClientOrderId(row.clientOrderId()).orElseThrow();
    assertThat(byCid.intentKey()).isEqualTo(exitKey);
  }

  @Test
  void findByClientOrderId_returnsEmpty_whenAbsent() {
    assertThat(journal.findByClientOrderId("no-such-client-order-id")).isEmpty();
  }

  @Test
  void markPlaceFailed_setsLastError_leavesStateRecorded() {
    // Issue #295: a broker rejection on the place path persists last_error without leaving the row
    // RECORDED-with-NULL-error (the silent outage the issue reports). State stays RECORDED so a
    // later retry can still transition it to SUBMITTED.
    journal.upsertIntent(intent("intent-A"));

    journal.markPlaceFailed("intent-A", "alpaca 422: client_order_id too long");

    JournaledOrder row = journal.findByIntentKey("intent-A").orElseThrow();
    assertThat(row.state()).isEqualTo(OrderState.RECORDED);
    assertThat(row.lastError()).isEqualTo("alpaca 422: client_order_id too long");
  }

  @Test
  void findByBrokerOrderId_returnsEmpty_forRecordedRow() {
    // RECORDED rows have no broker_order_id yet; the partial index excludes them and the lookup
    // returns empty rather than matching on NULL.
    journal.upsertIntent(intent("intent-A"));

    assertThat(journal.findByBrokerOrderId("anything")).isEmpty();
  }

  @Test
  void markExpired_fromSubmitted_flipsStateBumpsVersionSetsLastError() {
    journal.upsertIntent(intent("intent-A"));
    journal.markSubmittedIfRecorded("intent-A", "brk-A");
    JournaledOrder before = journal.findByIntentKey("intent-A").orElseThrow();

    boolean updated = journal.markExpired("intent-A");

    assertThat(updated).isTrue();
    JournaledOrder row = journal.findByIntentKey("intent-A").orElseThrow();
    assertThat(row.state()).isEqualTo(OrderState.EXPIRED);
    assertThat(row.lastError()).isEqualTo("broker terminal: EXPIRED");
    assertThat(row.lastStateAt()).isNotNull();
    assertThat(row.version()).isEqualTo(before.version() + 1);
  }

  @Test
  void markExpired_fromNonSubmitted_isNoOp() {
    // A row already FILLED (won the late-fill race) must NOT be demoted to EXPIRED.
    journal.upsertIntent(intent("intent-A"));
    journal.markSubmittedIfRecorded("intent-A", "brk-A");
    journal.markFilled(
        "intent-A", 5L, new BigDecimal("0.84"), OffsetDateTime.parse("2026-05-19T17:08:11Z"));
    JournaledOrder before = journal.findByIntentKey("intent-A").orElseThrow();

    boolean updated = journal.markExpired("intent-A");

    assertThat(updated).isFalse();
    JournaledOrder after = journal.findByIntentKey("intent-A").orElseThrow();
    assertThat(after.state()).isEqualTo(OrderState.FILLED);
    assertThat(after.version()).isEqualTo(before.version());
  }

  @Test
  void markBrokerRejected_fromSubmitted_flipsToErroredSetsReason() {
    journal.upsertIntent(intent("intent-A"));
    journal.markSubmittedIfRecorded("intent-A", "brk-A");
    JournaledOrder before = journal.findByIntentKey("intent-A").orElseThrow();

    boolean updated = journal.markBrokerRejected("intent-A", "broker terminal: REJECTED");

    assertThat(updated).isTrue();
    JournaledOrder row = journal.findByIntentKey("intent-A").orElseThrow();
    assertThat(row.state()).isEqualTo(OrderState.ERRORED);
    assertThat(row.lastError()).isEqualTo("broker terminal: REJECTED");
    assertThat(row.lastStateAt()).isNotNull();
    assertThat(row.version()).isEqualTo(before.version() + 1);
  }

  @Test
  void markBrokerRejected_fromNonSubmitted_isNoOp() {
    journal.upsertIntent(intent("intent-A"));
    journal.markSubmittedIfRecorded("intent-A", "brk-A");
    journal.markFilled(
        "intent-A", 5L, new BigDecimal("0.84"), OffsetDateTime.parse("2026-05-19T17:08:11Z"));
    JournaledOrder before = journal.findByIntentKey("intent-A").orElseThrow();

    boolean updated = journal.markBrokerRejected("intent-A", "broker terminal: REJECTED");

    assertThat(updated).isFalse();
    JournaledOrder after = journal.findByIntentKey("intent-A").orElseThrow();
    assertThat(after.state()).isEqualTo(OrderState.FILLED);
    assertThat(after.version()).isEqualTo(before.version());
  }

  @Test
  void markCancelledIfSubmitted_fromSubmitted_flipsStateBumpsVersion() {
    journal.upsertIntent(intent("intent-A"));
    journal.markSubmittedIfRecorded("intent-A", "brk-A");
    JournaledOrder before = journal.findByIntentKey("intent-A").orElseThrow();

    boolean updated = journal.markCancelledIfSubmitted("intent-A");

    assertThat(updated).isTrue();
    JournaledOrder row = journal.findByIntentKey("intent-A").orElseThrow();
    assertThat(row.state()).isEqualTo(OrderState.CANCELLED);
    assertThat(row.lastStateAt()).isNotNull();
    assertThat(row.version()).isEqualTo(before.version() + 1);
  }

  @Test
  void markCancelledIfSubmitted_fromNonSubmitted_isNoOp() {
    // The guarded variant must not clobber a FILLED row that won the late-fill race (#357).
    journal.upsertIntent(intent("intent-A"));
    journal.markSubmittedIfRecorded("intent-A", "brk-A");
    journal.markFilled(
        "intent-A", 5L, new BigDecimal("0.84"), OffsetDateTime.parse("2026-05-19T17:08:11Z"));
    JournaledOrder before = journal.findByIntentKey("intent-A").orElseThrow();

    boolean updated = journal.markCancelledIfSubmitted("intent-A");

    assertThat(updated).isFalse();
    JournaledOrder after = journal.findByIntentKey("intent-A").orElseThrow();
    assertThat(after.state()).isEqualTo(OrderState.FILLED);
    assertThat(after.version()).isEqualTo(before.version());
  }

  // ---------- Phase 2 (kill-switch realized re-source): findFilledBySideOnDay ----------

  @Test
  void findFilledBySideOnDay_returnsFilledRowsForSideOnEtDay_fifoOrdered() {
    // A SELL that FILLED at the broker whose PartialExitFilled audit was NEVER journaled (the F1
    // race) is still recorded here — broker truth. 18:00Z = 14:00 ET on 2026-06-29.
    String occ = "DRAM  260703C00016000";
    OffsetDateTime buyAt = OffsetDateTime.parse("2026-06-29T14:00:00Z");
    OffsetDateTime sellAt = OffsetDateTime.parse("2026-06-29T18:00:00Z");

    OrderIntent buy = intentWithOcc("buy-1", "sig-buy", occ);
    buy.setSide(OrderIntent.Side.BUY);
    journal.upsertIntent(buy);
    journal.markSubmittedIfRecorded("buy-1", "brk-buy");
    journal.markFilled("buy-1", 3L, new BigDecimal("2.3533"), buyAt);

    OrderIntent sell = intentWithOcc("sell-1", "sig-sell", occ);
    sell.setSide(OrderIntent.Side.SELL);
    journal.upsertIntent(sell);
    journal.markSubmittedIfRecorded("sell-1", "brk-sell");
    journal.markFilled("sell-1", 2L, new BigDecimal("1.84"), sellAt);

    var buys =
        journal.findFilledBySideOnDay("dev", "copytrade-v1", "BUY", LocalDate.of(2026, 6, 29));
    var sells =
        journal.findFilledBySideOnDay("dev", "copytrade-v1", "SELL", LocalDate.of(2026, 6, 29));

    assertThat(buys).extracting(JournaledOrder::intentKey).containsExactly("buy-1");
    assertThat(buys.get(0).filledQty()).isEqualTo(3L);
    assertThat(buys.get(0).avgFillPrice()).isEqualByComparingTo(new BigDecimal("2.3533"));
    assertThat(sells).extracting(JournaledOrder::intentKey).containsExactly("sell-1");
    assertThat(sells.get(0).avgFillPrice()).isEqualByComparingTo(new BigDecimal("1.84"));
  }

  @Test
  void findFilledBySideOnDay_excludesNonFilledAndOtherDaysAndOtherSides() {
    String occ = "DRAM  260703C00016000";
    LocalDate day = LocalDate.of(2026, 6, 29);

    // FILLED SELL on the target ET day (kept).
    OrderIntent onDay = intentWithOcc("sell-onday", "sig", occ);
    onDay.setSide(OrderIntent.Side.SELL);
    journal.upsertIntent(onDay);
    journal.markSubmittedIfRecorded("sell-onday", "brk-1");
    journal.markFilled(
        "sell-onday", 1L, new BigDecimal("1.00"), OffsetDateTime.parse("2026-06-29T18:00:00Z"));

    // FILLED SELL on a DIFFERENT ET day (excluded): 2026-06-30 18:00Z.
    OrderIntent otherDay = intentWithOcc("sell-otherday", "sig", occ);
    otherDay.setSide(OrderIntent.Side.SELL);
    journal.upsertIntent(otherDay);
    journal.markSubmittedIfRecorded("sell-otherday", "brk-2");
    journal.markFilled(
        "sell-otherday", 1L, new BigDecimal("1.00"), OffsetDateTime.parse("2026-06-30T18:00:00Z"));

    // SUBMITTED (not FILLED) SELL on the target day (excluded — no fill).
    OrderIntent notFilled = intentWithOcc("sell-notfilled", "sig", occ);
    notFilled.setSide(OrderIntent.Side.SELL);
    journal.upsertIntent(notFilled);
    journal.markSubmittedIfRecorded("sell-notfilled", "brk-3");

    // FILLED BUY on the target day (excluded from the SELL query).
    OrderIntent buy = intentWithOcc("buy-onday", "sig", occ);
    buy.setSide(OrderIntent.Side.BUY);
    journal.upsertIntent(buy);
    journal.markSubmittedIfRecorded("buy-onday", "brk-4");
    journal.markFilled(
        "buy-onday", 1L, new BigDecimal("2.00"), OffsetDateTime.parse("2026-06-29T18:00:00Z"));

    var sells = journal.findFilledBySideOnDay("dev", "copytrade-v1", "SELL", day);
    assertThat(sells).extracting(JournaledOrder::intentKey).containsExactly("sell-onday");
  }

  @Test
  void findFilledBySideOnDay_filtersTenantAndStrategy() {
    String occ = "DRAM  260703C00016000";
    LocalDate day = LocalDate.of(2026, 6, 29);
    OrderIntent foreign = intentWithOcc("sell-foreign", "sig", occ);
    foreign.setSide(OrderIntent.Side.SELL);
    foreign.setTenantId("other-tenant");
    journal.upsertIntent(foreign);
    journal.markSubmittedIfRecorded("sell-foreign", "brk-f");
    journal.markFilled(
        "sell-foreign", 1L, new BigDecimal("1.00"), OffsetDateTime.parse("2026-06-29T18:00:00Z"));

    assertThat(journal.findFilledBySideOnDay("dev", "copytrade-v1", "SELL", day)).isEmpty();
    assertThat(journal.findFilledBySideOnDay("other-tenant", "copytrade-v1", "SELL", day))
        .hasSize(1);
  }

  // ---------- Cross-day fix (PLAN-2026-07-22): findFilledBySide (lookback-bounded history)
  // --------

  @Test
  void findFilledBySide_returnsWithinWindowDaysFifoOrdered_noPerDayPredicate() {
    // The lookback-bounded sibling drops the per-day EQUALITY predicate (keeping only the lower
    // bound): a prior-day (D1) entry AND a same-day (D2) exit — both within the window — are
    // returned, FIFO-ordered, so the exec impl can day-scope in-memory and match the D2 exit
    // against its REAL D1 basis instead of crediting phantom raw proceeds.
    String occ = "AAPL  260727C00330000";
    OffsetDateTime d1 = OffsetDateTime.parse("2026-07-21T14:00:00Z");
    OffsetDateTime d2 = OffsetDateTime.parse("2026-07-22T14:00:00Z");

    OrderIntent buy = intentWithOcc("buy-d1", "sig-buy", occ);
    buy.setSide(OrderIntent.Side.BUY);
    journal.upsertIntent(buy);
    journal.markSubmittedIfRecorded("buy-d1", "brk-buy");
    journal.markFilled("buy-d1", 50L, new BigDecimal("1.99"), d1);

    OrderIntent sell = intentWithOcc("sell-d2", "sig-sell", occ);
    sell.setSide(OrderIntent.Side.SELL);
    journal.upsertIntent(sell);
    journal.markSubmittedIfRecorded("sell-d2", "brk-sell");
    journal.markFilled("sell-d2", 11L, new BigDecimal("1.88"), d2);

    // sinceEtDay well before D1 — both fills are within the window.
    LocalDate since = LocalDate.of(2026, 4, 23); // D2 − 90d
    // Within-window BUY: the prior-day entry is returned (the per-day query scoped to D2 would miss
    // it — that miss was the phantom).
    var buys = journal.findFilledBySide("dev", "copytrade-v1", "BUY", since);
    assertThat(buys).extracting(JournaledOrder::intentKey).containsExactly("buy-d1");
    assertThat(buys.get(0).filledQty()).isEqualTo(50L);

    var sells = journal.findFilledBySide("dev", "copytrade-v1", "SELL", since);
    assertThat(sells).extracting(JournaledOrder::intentKey).containsExactly("sell-d2");
    assertThat(sells.get(0).avgFillPrice()).isEqualByComparingTo(new BigDecimal("1.88"));

    // The day-scoped query still excludes the prior-day BUY (contrast that pins the fix's premise).
    assertThat(
            journal.findFilledBySideOnDay("dev", "copytrade-v1", "BUY", LocalDate.of(2026, 7, 22)))
        .isEmpty();
  }

  @Test
  void findFilledBySide_excludesRowsOlderThanLookbackWindow() {
    // Lookback bound (PLAN-2026-07-22 review follow-up): an entry whose ET fill date is BEFORE
    // sinceEtDay is excluded (the query is bounded, not full-history), while a within-window entry
    // on/after the boundary is returned. Boundary is inclusive (>=).
    String occ = "AAPL  260727C00330000";
    OffsetDateTime old = OffsetDateTime.parse("2026-04-01T14:00:00Z"); // well before the window
    OffsetDateTime boundaryDay = OffsetDateTime.parse("2026-04-23T14:00:00Z"); // == sinceEtDay ET

    OrderIntent oldBuy = intentWithOcc("buy-old", "sig-old", occ);
    oldBuy.setSide(OrderIntent.Side.BUY);
    journal.upsertIntent(oldBuy);
    journal.markSubmittedIfRecorded("buy-old", "brk-old");
    journal.markFilled("buy-old", 7L, new BigDecimal("1.11"), old);

    OrderIntent boundaryBuy = intentWithOcc("buy-boundary", "sig-boundary", occ);
    boundaryBuy.setSide(OrderIntent.Side.BUY);
    journal.upsertIntent(boundaryBuy);
    journal.markSubmittedIfRecorded("buy-boundary", "brk-boundary");
    journal.markFilled("buy-boundary", 3L, new BigDecimal("2.22"), boundaryDay);

    LocalDate since = LocalDate.of(2026, 4, 23); // 14:00Z on this date == 10:00 EDT, same ET day
    var buys = journal.findFilledBySide("dev", "copytrade-v1", "BUY", since);

    // Only the boundary (inclusive) row survives; the older row is bounded out.
    assertThat(buys).extracting(JournaledOrder::intentKey).containsExactly("buy-boundary");
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
