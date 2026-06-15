package com.ohmytradeagent.exec.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.table;

import com.ohmytradeagent.contract.BrokerPosition;
import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.exec.broker.stub.StubBroker;
import com.ohmytradeagent.exec.journal.JooqOrderIntentJournal;
import com.ohmytradeagent.exec.journal.JournaledOrder;
import com.ohmytradeagent.exec.journal.OrderState;
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

/**
 * Issue #239 exec-side coverage for the two new {@link
 * com.ohmytradeagent.contract.activities.ReconciliationExecActivity} methods backing orphan
 * adoption: {@code brokerGetPositionByOcc} (broker truth filter) and {@code
 * journalReconcileToFilled} (idempotent SUBMITTED -> FILLED terminalization). Mirrors the
 * Testcontainers pattern of {@link ExecActivitiesImplIT}.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class ReconciliationExecActivityImplIT {

  private static final String TENANT = "dev";
  private static final String STRATEGY = "copytrade-v1";
  private static final String OCC = "UNH   260618C00400000";
  private static final String OTHER_OCC = "NVDA  260516C00140000";

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  private static java.sql.Connection conn;
  private static DSLContext dsl;
  private JooqOrderIntentJournal journal;
  private StubBroker broker;
  private ReconciliationExecActivityImpl exec;

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
  void resetState() {
    dsl.deleteFrom(table("order_intent_journal")).execute();
    journal = new JooqOrderIntentJournal(dsl);
    broker = new StubBroker();
    exec =
        new ReconciliationExecActivityImpl(
            journal,
            new com.ohmytradeagent.exec.broker.FixedBrokerClientRegistry(broker),
            "alpaca-paper");
  }

  @Test
  void brokerGetPositionByOcc_returnsHeldLot_whenBrokerHoldsTheOcc() {
    broker.setOpenPosition(OCC, 5L, new BigDecimal("3.40"));

    BrokerPosition lot = exec.brokerGetPositionByOcc(TENANT, STRATEGY, OCC);

    assertThat(lot).isNotNull();
    assertThat(lot.getOptionSymbol()).isEqualTo(OCC);
    assertThat(lot.getQty()).isEqualTo(5L);
    assertThat(lot.getAvgEntryPrice()).isEqualByComparingTo(new BigDecimal("3.40"));
  }

  @Test
  void brokerGetPositionByOcc_returnsNull_whenBrokerDoesNotHoldTheOcc() {
    broker.setOpenPosition(OTHER_OCC, 2L, new BigDecimal("1.10"));

    BrokerPosition lot = exec.brokerGetPositionByOcc(TENANT, STRATEGY, OCC);

    assertThat(lot).isNull();
  }

  @Test
  void journalReconcileToFilled_flipsSubmittedToFilled_thenNoopOnRepeat() {
    OffsetDateTime filledAt = OffsetDateTime.parse("2026-05-19T17:08:11Z");
    journal.upsertIntent(intent("intent-A"));
    journal.markSubmittedIfRecorded("intent-A", "broker-A");
    assertThat(journal.findByIntentKey("intent-A").orElseThrow().state())
        .isEqualTo(OrderState.SUBMITTED);

    boolean first = exec.journalReconcileToFilled("intent-A", 5L, new BigDecimal("3.40"), filledAt);

    assertThat(first).isTrue();
    JournaledOrder row = journal.findByIntentKey("intent-A").orElseThrow();
    assertThat(row.state()).isEqualTo(OrderState.FILLED);
    assertThat(row.filledQty()).isEqualTo(5L);
    assertThat(row.avgFillPrice()).isEqualByComparingTo(new BigDecimal("3.40"));
    assertThat(row.filledAt()).isEqualTo(filledAt);

    // Idempotent: a repeat call does not re-transition (already terminal).
    boolean second =
        exec.journalReconcileToFilled("intent-A", 9L, new BigDecimal("9.99"), filledAt);

    assertThat(second).isFalse();
    JournaledOrder unchanged = journal.findByIntentKey("intent-A").orElseThrow();
    assertThat(unchanged.state()).isEqualTo(OrderState.FILLED);
    assertThat(unchanged.filledQty()).isEqualTo(5L);
    assertThat(unchanged.avgFillPrice()).isEqualByComparingTo(new BigDecimal("3.40"));
  }

  private OrderIntent intent(String key) {
    OrderIntent i = new OrderIntent();
    i.setSchemaVersion(1L);
    i.setIntentKey(key);
    i.setSignalId("sig-1");
    i.setTenantId(TENANT);
    i.setStrategyId(STRATEGY);
    i.setBrokerTarget(OrderIntent.BrokerTarget.PAPER);
    i.setOptionSymbol(OCC);
    i.setSide(OrderIntent.Side.BUY);
    i.setQty(5L);
    i.setLimitPrice(new BigDecimal("3.30"));
    i.setRecordedAt(OffsetDateTime.parse("2026-05-13T17:22:31Z"));
    return i;
  }
}
