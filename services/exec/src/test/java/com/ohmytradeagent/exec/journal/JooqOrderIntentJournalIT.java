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
  void findByIntentKey_missing_returnsEmpty() {
    assertThat(journal.findByIntentKey("nope")).isEmpty();
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
}
