package com.ohmytradeagent.exec.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.table;

import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.contract.OrderIntentResult;
import com.ohmytradeagent.exec.broker.BrokerOrderStatus;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import com.ohmytradeagent.exec.broker.PlaceOrderRequest;
import com.ohmytradeagent.exec.broker.PlaceOrderResponse;
import com.ohmytradeagent.exec.broker.stub.StubBroker;
import com.ohmytradeagent.exec.journal.JooqOrderIntentJournal;
import com.ohmytradeagent.exec.journal.OrderState;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Phase 2b idempotency IT matrix (plan line 452 Done-when). Simulates "crash between journal write
 * and broker call" by chaining real Activity invocations against a long-lived Postgres but throwing
 * from the broker on the first attempt, then re-invoking and asserting no duplicate broker call
 * landed.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class ExecActivitiesImplIT {

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  private static java.sql.Connection conn;
  private static DSLContext dsl;
  private JooqOrderIntentJournal journal;
  private CountingStubBroker broker;
  private ExecActivitiesImpl exec;

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
  void resetState() {
    dsl.deleteFrom(table("order_intent_journal")).execute();
    journal = new JooqOrderIntentJournal(dsl);
    broker = new CountingStubBroker();
    exec = new ExecActivitiesImpl(journal, broker);
  }

  @Test
  void happyPath_oneJournalRow_oneBrokerCall_stateSubmitted() {
    OrderIntent intent = intent("intent-A");

    OrderIntentResult result = exec.placeOrder(intent);

    assertThat(result.getState()).isEqualTo(OrderIntentResult.State.SUBMITTED);
    assertThat(result.getBrokerOrderId()).isEqualTo("stub-intent-A");
    assertThat(broker.placeCallsFor("intent-A")).isEqualTo(1);
    assertThat(journalRowCount("intent-A")).isEqualTo(1L);
  }

  @Test
  void crashBetweenRecordAndBroker_reinvoke_oneJournalRow_brokerCalledTwiceSameIdSubmitted() {
    OrderIntent intent = intent("intent-A");
    broker.failNextPlaceCalls(1, "simulated network failure pre-submit");

    assertThatThrownBy(() -> exec.placeOrder(intent))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("pre-submit");

    OrderIntentResult result = exec.placeOrder(intent);

    assertThat(result.getState()).isEqualTo(OrderIntentResult.State.SUBMITTED);
    assertThat(result.getBrokerOrderId()).isEqualTo("stub-intent-A");
    assertThat(broker.placeCallsFor("intent-A")).isEqualTo(2);
    assertThat(journalRowCount("intent-A")).isEqualTo(1L);
  }

  @Test
  void crashBetweenBrokerAndMarkSubmitted_reinvoke_idempotent() {
    OrderIntent intent = intent("intent-A");
    broker.failAfterPlace = true;

    // First call: broker accepts, then the post-broker hook fails.
    assertThatThrownBy(() -> exec.placeOrder(intent)).isInstanceOf(RuntimeException.class);
    broker.failAfterPlace = false;

    OrderIntentResult result = exec.placeOrder(intent);

    assertThat(result.getState()).isEqualTo(OrderIntentResult.State.SUBMITTED);
    assertThat(result.getBrokerOrderId()).isEqualTo("stub-intent-A");
    assertThat(journalRowCount("intent-A")).isEqualTo(1L);
    // Broker was called twice (once on the failed attempt, once on retry) and
    // returned the same broker_order_id thanks to client_order_id dedup.
    assertThat(broker.placeCallsFor("intent-A")).isEqualTo(2);
  }

  @Test
  void reinvokeAfterSuccess_shortCircuits_brokerNotCalledAgain() {
    OrderIntent intent = intent("intent-A");
    exec.placeOrder(intent);

    exec.placeOrder(intent);

    assertThat(broker.placeCallsFor("intent-A")).isEqualTo(1);
  }

  @Test
  void concurrentReinvoke_singleJournalRow_singleStateTransition() throws Exception {
    OrderIntent intent = intent("intent-A");
    int threads = 8;
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch fire = new CountDownLatch(1);
    AtomicInteger errors = new AtomicInteger();

    Thread[] pool = new Thread[threads];
    for (int i = 0; i < threads; i++) {
      pool[i] =
          new Thread(
              () -> {
                ready.countDown();
                try {
                  fire.await();
                  exec.placeOrder(intent);
                } catch (RuntimeException | InterruptedException e) {
                  errors.incrementAndGet();
                }
              });
      pool[i].start();
    }
    ready.await();
    fire.countDown();
    for (Thread t : pool) t.join();

    assertThat(errors.get()).isZero();
    assertThat(journalRowCount("intent-A")).isEqualTo(1L);
    assertThat(journal.findByIntentKey("intent-A").orElseThrow().state())
        .isEqualTo(OrderState.SUBMITTED);
    // Broker may have been called more than once across threads, but always with
    // the same client_order_id → all returns same broker_order_id.
    assertThat(broker.placeCallsFor("intent-A")).isGreaterThanOrEqualTo(1);
    assertThat(broker.distinctBrokerOrderIds()).isEqualTo(1);
  }

  @Test
  void cancelOrder_onSubmitted_flipsState() {
    OrderIntent intent = intent("intent-A");
    exec.placeOrder(intent);

    OrderIntentResult result = exec.cancelOrder("intent-A");

    assertThat(result.getState()).isEqualTo(OrderIntentResult.State.CANCELLED);
    assertThat(broker.getOrderStatus("stub-intent-A")).isEqualTo(BrokerOrderStatus.CANCELLED);
  }

  @Test
  void cancelOrder_onAlreadyFilled_keepsSubmittedSetsLastError() {
    OrderIntent intent = intent("intent-A");
    exec.placeOrder(intent);
    broker.forceStatusForTest("stub-intent-A", BrokerOrderStatus.FILLED);

    OrderIntentResult result = exec.cancelOrder("intent-A");

    assertThat(result.getState()).isEqualTo(OrderIntentResult.State.SUBMITTED);
    assertThat(result.getLastError()).isEqualTo("order already filled");
  }

  private long journalRowCount(String intentKey) {
    return dsl.select(count())
        .from(table("order_intent_journal"))
        .where(org.jooq.impl.DSL.field("intent_key", String.class).eq(intentKey))
        .fetchOneInto(Long.class);
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

  /** StubBroker decorator that counts calls + injects controlled failures. */
  static final class CountingStubBroker implements OptionsBroker {
    private final StubBroker delegate = new StubBroker();
    private final java.util.Map<String, AtomicInteger> placeCalls = new java.util.HashMap<>();
    private final java.util.Set<String> distinctIds = new java.util.HashSet<>();
    private int placeFailuresRemaining = 0;
    private String placeFailureMessage;
    volatile boolean failAfterPlace = false;

    void failNextPlaceCalls(int n, String message) {
      this.placeFailuresRemaining = n;
      this.placeFailureMessage = message;
    }

    int placeCallsFor(String clientOrderId) {
      AtomicInteger c = placeCalls.get(clientOrderId);
      return c == null ? 0 : c.get();
    }

    int distinctBrokerOrderIds() {
      return distinctIds.size();
    }

    @Override
    public synchronized PlaceOrderResponse placeOrder(PlaceOrderRequest request) {
      placeCalls
          .computeIfAbsent(request.clientOrderId(), k -> new AtomicInteger())
          .incrementAndGet();
      if (placeFailuresRemaining > 0) {
        placeFailuresRemaining--;
        throw new RuntimeException(placeFailureMessage);
      }
      PlaceOrderResponse r = delegate.placeOrder(request);
      distinctIds.add(r.brokerOrderId());
      if (failAfterPlace) {
        throw new RuntimeException("simulated post-broker failure");
      }
      return r;
    }

    @Override
    public com.ohmytradeagent.exec.broker.CancelResponse cancelOrder(String brokerOrderId) {
      return delegate.cancelOrder(brokerOrderId);
    }

    @Override
    public BrokerOrderStatus getOrderStatus(String brokerOrderId) {
      return delegate.getOrderStatus(brokerOrderId);
    }

    void forceStatusForTest(String brokerOrderId, BrokerOrderStatus status) {
      delegate.forceStatusForTest(brokerOrderId, status);
    }
  }
}
